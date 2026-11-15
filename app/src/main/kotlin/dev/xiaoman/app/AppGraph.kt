package dev.xiaoman.app

import android.content.Context
import android.util.Log
import dev.xiaoman.agent.XiaomanAgent
import dev.xiaoman.api.Agent
import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.ModelRole
import dev.xiaoman.app.diary.DiaryEntry
import dev.xiaoman.app.diary.DiaryStore
import dev.xiaoman.app.diary.DiarySummarizer
import dev.xiaoman.app.media.MediaEngineRouter
import dev.xiaoman.app.quick.QuickCommand
import dev.xiaoman.app.quick.QuickCommandStore
import dev.xiaoman.app.session.AgentSession
import dev.xiaoman.app.session.AndroidStepText
import dev.xiaoman.app.session.ChatHistoryStore
import dev.xiaoman.app.session.SessionTexts
import dev.xiaoman.app.session.TaskQueueStore
import dev.xiaoman.app.session.TaskThreadStore
import dev.xiaoman.app.settings.AppSettings
import dev.xiaoman.app.settings.ModelSettings
import dev.xiaoman.app.settings.SettingsStore
import dev.xiaoman.app.trace.RecordingDevice
import dev.xiaoman.app.trace.TraceWriter
import dev.xiaoman.hands.AccessibilityDevice
import dev.xiaoman.hands.MutableStopSignal
import dev.xiaoman.hands.XiaomanAccessibilityService
import dev.xiaoman.llm.LlmClientFactory
import dev.xiaoman.llm.LlmClients
import dev.xiaoman.llm.ModelCatalogClient
import dev.xiaoman.llm.ModelProbe
import dev.xiaoman.llm.ProbeResult
import dev.xiaoman.llm.RemoteModel
import dev.xiaoman.app.settings.McpSettingsStore
import dev.xiaoman.mcp.McpCatalog
import dev.xiaoman.mcp.McpConfig
import dev.xiaoman.mcp.McpPlatform
import dev.xiaoman.mcp.McpSelection
import dev.xiaoman.mcp.McpToolProvider
import dev.xiaoman.mcp.ServerStatus
import dev.xiaoman.mcp.UserChannelApprovals
import dev.xiaoman.memory.FileMemoryStore
import dev.xiaoman.sentinel.PolicySentinel
import dev.xiaoman.skills.DirectorySkillRepository
import dev.xiaoman.skills.FileUserSkillStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.IOException

/**
 * Manual dependency container. Everything is built lazily; [start] runs once from
 * [XiaomanApp.onCreate].
 *
 * Folders (`<external files>` = `/sdcard/Android/data/dev.xiaoman.app/files`):
 * `memory/`, `skills/` (user skills, override bundled ones), `traces/`, optional `mcp.json` (MCP servers,
 * HTTP only; keys are written in the file because Android has no environment); bundled skills are
 * extracted to `<files>/bundled-skills/`.
 */
class AppGraph(context: Context, private val llm: LlmClientFactory = LlmClients.default) {
    private val app: Context = context.applicationContext

    /** Process-wide scope; the session runs agent work here. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val externalRoot: File = app.getExternalFilesDir(null) ?: File(app.filesDir, "external")
    val memoryDir = File(externalRoot, "memory")
    val skillsDir = File(externalRoot, "skills")
    val tracesDir = File(externalRoot, "traces")
    val bundledSkillsDir = File(app.filesDir, "bundled-skills")
    val mcpFile = File(externalRoot, "mcp.json")

    val settings: SettingsStore by lazy { SettingsStore.create(app) }

    /**
     * Built-in ASR / TTS / OCR. With the default AUTO mode a DeepSeek provider automatically uses
     * the official `/v1/tools/{asr,tts,ocr}` endpoints and the same API key; other providers fall
     * back to the Android platform engines (OCR via the chat model's vision channel).
     */
    val media by lazy {
        MediaEngineRouter(
            context = app,
            settings = { settings.current },
            chat = {
                val current = settings.current
                if (current.chat.configured) llm.create(current.chat.toModelConfig(ModelRole.CHAT)) else null
            },
        )
    }

    val memory by lazy { FileMemoryStore(memoryDir) }

    /**
     * Text of the message currently being read aloud (auto-read or a tap on one bubble); null when
     * idle. Keyed by text so the per-message button can show "stop" for the message being spoken.
     */
    val speaking = MutableStateFlow<String?>(null)
    private var speakJob: Job? = null

    /** Last read-aloud failure; the UI shows it once and calls [clearSpeakError]. */
    val speakError = MutableStateFlow<String?>(null)

    fun clearSpeakError() {
        speakError.value = null
    }

    /** Read one message aloud, replacing any playback already in progress. */
    fun speak(text: String) {
        if (text.isBlank()) return
        stopSpeaking()
        speaking.value = text
        speakJob = scope.launch {
            try {
                media.current().tts.speak(text, settings.current.ttsVoice)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                Log.w(TAG, "tts: ${error.message}")
                speakError.value = error.message ?: error.javaClass.simpleName
            } finally {
                if (speaking.value == text) speaking.value = null
            }
        }
    }

    /** Stop the current utterance, whoever started it. */
    fun stopSpeaking() {
        speakJob?.cancel()
        speakJob = null
        runCatching { media.current().tts.stop() }
        speaking.value = null
    }

    /** Bubble button behaviour: the message being spoken stops, any other message starts. */
    fun toggleSpeaking(text: String) {
        if (speaking.value == text) stopSpeaking() else speak(text)
    }

    val skills by lazy { DirectorySkillRepository(listOf(bundledSkillsDir, skillsDir)) }

    /** Reads and writes user skills under [skillsDir]; bundled skills under [bundledSkillsDir] are never modified. */
    val userSkillStore by lazy { FileUserSkillStore(bundledSkillsDir, skillsDir) }

    /** Chat and quick-command files live outside the memory folder so they are easy to back up or reset. */
    private val chatDir = File(externalRoot, "chat")

    val chatHistory by lazy {
        ChatHistoryStore(File(chatDir, "history.json")) { settings.current.chatHistoryLimit }
    }

    /** Xiaoman's daily activity diary, shown in the "小满" tab. */
    val diary by lazy { DiaryStore(File(externalRoot, "diary/diary.json")) }

    /** Recaps a diary day with the configured chat model API; no operator model needed. */
    private val diarySummarizer by lazy {
        DiarySummarizer(llm, chatConfig = {
            settings.current.chat.takeIf { it.configured }?.toModelConfig(ModelRole.CHAT)
        })
    }

    /**
     * Calls the model API, then writes the returned summary into the diary file so it
     * survives restarts. Failure is returned to the UI instead of being swallowed.
     */
    suspend fun generateDiarySummary(date: String, entries: List<DiaryEntry>): Result<String> =
        diarySummarizer.summarize(date, entries).onSuccess { text ->
            diary.putSummary(date, text, entries.size)
        }

    /** Phone tasks as their own conversations, linked from the main chat. */
    val taskThreads by lazy { TaskThreadStore(File(chatDir, "tasks.json")) }

    /** Phone tasks dispatched from the task center, waiting for the single execution slot. */
    val taskQueue by lazy { TaskQueueStore(File(chatDir, "queue.json")) }

    val quickCommands by lazy {
        QuickCommandStore(File(chatDir, "quick-commands.json"), defaultQuickCommands())
    }

    val notifier by lazy { Notifier(app) { settings.current.notificationsEnabled } }

    val reminderScheduler by lazy { AlarmReminderScheduler(app) }

    /** Services ticked on the 服务 screen; keys live in the encrypted settings file. */
    val mcpSettings: McpSettingsStore by lazy { McpSettingsStore.create(app, settings.secret) }

    private var cachedMcp: Pair<McpSelection, McpToolProvider?>? = null

    /** MCP config of [selection] plus the HTTP servers of the optional `mcp.json` (stdio needs a desktop). */
    fun mcpConfig(selection: McpSelection): McpConfig {
        val ticked = McpCatalog.builtIn.toConfig(selection, McpPlatform.ANDROID)
        val file = McpConfig.load(mcpFile, emptyMap())
        val fileServers = file.servers.filter { it.enabled && it.url != null && ticked.servers.none { t -> t.name == it.name } }
        (ticked.warnings + file.warnings).forEach { Log.w(TAG, "mcp: $it") }
        return McpConfig(ticked.servers + fileServers, ticked.warnings + file.warnings)
    }

    /** The provider for [selection], rebuilt (and the old one closed) when the ticked services change. */
    @Synchronized
    private fun mcpFor(selection: McpSelection): McpToolProvider? {
        cachedMcp?.let { (s, provider) -> if (s == selection) return provider }
        cachedMcp?.second?.close()
        val config = mcpConfig(selection)
        val provider = if (config.servers.isEmpty()) null else McpToolProvider(config, UserChannelApprovals(session))
        cachedMcp = selection to provider
        return provider
    }

    /** Connects to the services of [selection] once, for the 测试连接 button; never kept. */
    suspend fun testMcp(selection: McpSelection): List<ServerStatus> {
        val config = mcpConfig(selection)
        if (config.servers.isEmpty()) return emptyList()
        return McpToolProvider(config, { false }).use { it.status(refresh = true) }
    }

    val runIds = RunIds()
    val uiVisible = MutableStateFlow(false)

    val traces by lazy {
        TraceWriter(
            root = tracesDir,
            secrets = { settings.current.secrets },
            log = { Log.i(TAG, it) },
            warn = { Log.w(TAG, it) },
        )
    }

    val accessibilityDevice by lazy { AccessibilityDevice({ XiaomanAccessibilityService.instance.value }, app) }

    private val device by lazy { RecordingDevice(accessibilityDevice, traces::saveScreenshot) }

    val sentinel by lazy { PolicySentinel(approvals = session, audit = traces, selfPackage = app.packageName) }

    val session: AgentSession by lazy {
        AgentSession(
            scope = scope,
            agent = ::agent,
            runIds = runIds,
            stopSignal = MutableStopSignal(),
            trace = traces,
            onTaskEnd = { sentinel.endTask(it) },
            texts = SessionTexts(
                notConfigured = app.getString(R.string.session_not_configured),
                busy = app.getString(R.string.session_busy),
                stopped = app.getString(R.string.session_stopped),
                approvedOnce = { app.getString(R.string.approval_resolved_allowed, it) },
                approvedTask = { app.getString(R.string.approval_resolved_task, it) },
                denied = { app.getString(R.string.approval_resolved_denied, it) },
                approvalPending = { app.getString(R.string.status_approval_pending, it) },
                interrupted = app.getString(R.string.queue_interrupted),
                resumed = { app.getString(R.string.task_resumed, it) },
            ),
            ready = { startup.await() },
            history = chatHistory,
            taskThreads = taskThreads,
            queue = taskQueue,
            historyLimit = { settings.current.chatHistoryLimit },
            stepText = AndroidStepText(app),
        )
    }

    /** Runs a one-shot probe against the values currently typed in Settings, without saving them. */
    suspend fun probeModel(settings: ModelSettings, role: ModelRole): ProbeResult =
        ModelProbe.probe(llm.create(settings.toModelConfig(role).copy(timeoutSeconds = 30)), role)

    /** Fetches the provider's OpenAI-compatible model list for the Settings picker. */
    suspend fun listModels(baseUrl: String, apiKey: String): List<RemoteModel> =
        ModelCatalogClient().list(baseUrl, apiKey)

    private fun defaultQuickCommands(): List<QuickCommand> = listOf(
        QuickCommand(
            id = "default-notebook",
            title = app.getString(R.string.quick_default_notebook_title),
            prompt = app.getString(R.string.quick_default_notebook_prompt),
            icon = QuickCommand.ICON_SHOPPING,
        ),
        QuickCommand(
            id = "default-water",
            title = app.getString(R.string.quick_default_water_title),
            prompt = app.getString(R.string.quick_default_water_prompt),
            icon = QuickCommand.ICON_BELL,
        ),
        QuickCommand(
            id = "default-preference",
            title = app.getString(R.string.quick_default_preference_title),
            prompt = app.getString(R.string.quick_default_preference_prompt),
            icon = QuickCommand.ICON_MEMORY,
        ),
        QuickCommand(
            id = "default-package",
            title = app.getString(R.string.quick_default_package_title),
            prompt = app.getString(R.string.quick_default_package_prompt),
            icon = QuickCommand.ICON_PLAN,
        ),
        QuickCommand(
            id = "default-summary",
            title = app.getString(R.string.quick_default_summary_title),
            prompt = app.getString(R.string.quick_default_summary_prompt),
            icon = QuickCommand.ICON_SPARK,
        ),
        QuickCommand(
            id = "default-translate",
            title = app.getString(R.string.quick_default_translate_title),
            prompt = app.getString(R.string.quick_default_translate_prompt),
            icon = QuickCommand.ICON_MEMORY,
        ),
    )

    /** Bundled skills extracted and skills loaded once. */
    lateinit var startup: Deferred<Unit>
        private set

    private var cachedAgent: Pair<Pair<AppSettings, McpSelection>, Agent>? = null

    /** The agent for the current settings, rebuilt when they change; null until both models are configured. */
    @Synchronized
    fun agent(): Agent? {
        val current = settings.current
        if (!current.configured) return null
        val key = current to mcpSettings.current
        cachedAgent?.let { (k, agent) -> if (k == key) return agent }
        val agent = XiaomanAgent(
            chat = llm.create(current.chat.toModelConfig(ModelRole.CHAT)),
            operator = llm.create(current.operator.toModelConfig(ModelRole.OPERATOR)),
            device = device,
            memory = memory,
            skills = skills,
            sentinel = sentinel,
            users = session,
            reminders = reminderScheduler,
            config = current.agentConfig(),
            idFactory = runIds::next,
            externalTools = mcpFor(key.second),
            userSkillStore = userSkillStore,
        )
        cachedAgent = key to agent
        return agent
    }

    fun start() {
        startup = scope.async(Dispatchers.IO) { prepareSkills() }
        notifier.createChannels()
        OverlayController(session, accessibilityDevice, uiVisible = uiVisible, fallbackTitle = app.getString(R.string.app_name)).start(scope)
        scope.launch(Dispatchers.Main.immediate) {
            session.busy.collect { busy -> if (busy) AgentService.start(app) }
        }
        // Read the finished reply aloud with the built-in TTS engine. Only the final Reply is
        // spoken (never streaming deltas), so a long answer is synthesized once as one clip.
        scope.launch {
            session.events.collect { event ->
                if (event is AgentEvent.Reply && event.text.isNotBlank() && settings.current.readRepliesAloud) {
                    speak(event.text)
                }
            }
        }
        // Tasks still queued from last time continue once skills and the accessibility bridge are
        // ready; without the bridge the operator loop would fail on its first observation.
        scope.launch {
            val deadline = System.currentTimeMillis() + 5_000
            while (XiaomanAccessibilityService.instance.value == null && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(200)
            }
            session.resumeQueue()
        }
        scope.launch(Dispatchers.Main.immediate) { session.approval.collect(notifier::showApproval) }
        scope.launch(Dispatchers.Main.immediate) { session.question.collect(notifier::showQuestion) }
        scope.launch {
            // Every finished phone task and memory update becomes one diary line for the "小满" tab.
            var goal = ""
            session.events.collect { event ->
                when (event) {
                    is AgentEvent.PhoneTaskStarted -> goal = event.goal
                    is AgentEvent.PhoneTaskFinished -> {
                        val title = goal.ifBlank { event.summary }
                        diary.add(
                            kind = if (event.success) "task_done" else "task_failed",
                            title = title,
                            detail = event.summary.takeIf { it.isNotBlank() && it != title }.orEmpty(),
                        )
                        goal = ""
                    }
                    is AgentEvent.MemoryUpdated -> diary.add("memory", event.permalink)
                    else -> Unit
                }
            }
        }
    }

    private suspend fun prepareSkills() {
        try {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            val version = "${BuildConfig.VERSION_CODE}-${info.lastUpdateTime}"
            if (BundledSkills.extract(app.assets, bundledSkillsDir, version)) Log.i(TAG, "skills: bundled skills extracted ($version)")
            skillsDir.mkdirs()
            memoryDir.mkdirs()
        } catch (e: IOException) {
            Log.w(TAG, "skills: cannot extract bundled skills: ${e.message}")
        }
        val result = skills.reload()
        logSkillProblems(result.skills.size, result.problems)
    }

    fun logSkillProblems(count: Int, problems: List<dev.xiaoman.api.SkillProblem>) {
        Log.i(TAG, "reload-skills: $count skills, ${problems.size} problems")
        problems.forEach { p -> Log.w(TAG, "reload-skills: problem ${p.source}${p.line?.let { ":$it" }.orEmpty()} ${p.message}") }
    }

    companion object {
        const val TAG = "Xiaoman"
    }
}
