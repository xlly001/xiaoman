package dev.xiaoman.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.xiaoman.api.LlmException
import dev.xiaoman.api.ModelRole
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.BuildConfig
import dev.xiaoman.app.R
import dev.xiaoman.app.settings.AppSettings
import dev.xiaoman.app.settings.ModelDefaults
import dev.xiaoman.app.settings.ModelProfile
import dev.xiaoman.app.settings.ModelSettings
import dev.xiaoman.app.settings.ProviderPreset
import dev.xiaoman.app.settings.Providers
import dev.xiaoman.app.settings.SettingsForm
import dev.xiaoman.llm.ProbeResult
import dev.xiaoman.llm.RemoteModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private data class ModelTestState(
    val testing: Boolean = false,
    val result: ProbeResult? = null,
    val role: ModelRole? = null,
)

private data class FetchError(val statusCode: Int?, val message: String)

@Composable
fun SettingsScreen(graph: AppGraph) {
    val store = graph.settings
    var form by remember { mutableStateOf(SettingsForm.of(store.current)) }
    var saved by remember { mutableStateOf(false) }
    var providerMenu by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var fetching by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<FetchError?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var manualAdd by remember { mutableStateOf(false) }
    var chatTest by remember { mutableStateOf(ModelTestState()) }
    var operatorTest by remember { mutableStateOf(ModelTestState()) }
    var testingModel by remember { mutableStateOf<String?>(null) }
    var modelResults by remember { mutableStateOf<Map<String, ProbeResult>>(emptyMap()) }
    var clearTarget by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val provider = Providers.byId(form.providerId)
    val parsed = form.toSettings()

    fun changeProvider(next: ProviderPreset) {
        form = form.copy(
            providerId = next.id,
            chat = form.chat.copy(baseUrl = next.baseUrl, model = "", displayName = ""),
            operator = form.operator.copy(baseUrl = next.baseUrl, model = "", displayName = ""),
            models = emptyList(),
        )
        fetchError = null
        saved = false
    }

    fun changeBaseUrl(value: String) {
        form = form.copy(
            chat = form.chat.copy(baseUrl = value),
            operator = form.operator.copy(baseUrl = value),
        )
        saved = false
    }

    fun changeKey(value: String) {
        form = form.copy(
            chat = form.chat.copy(apiKey = value),
            operator = form.operator.copy(apiKey = value),
        )
        saved = false
    }

    fun applyProfile(profile: ModelProfile, role: ModelRole) {
        val target = if (role == ModelRole.CHAT) form.chat else form.operator
        val updated = target.copy(
            baseUrl = form.chat.baseUrl,
            apiKey = form.chat.apiKey,
            model = profile.id,
            temperature = profile.temperature?.toString().orEmpty(),
            maxTokens = profile.maxTokens?.toString().orEmpty(),
            contextWindow = profile.contextWindow?.toString().orEmpty(),
            supportsVision = profile.supportsVision,
            displayName = profile.title,
        )
        form = if (role == ModelRole.CHAT) form.copy(chat = updated) else form.copy(operator = updated)
        saved = false
    }

    fun fetchModels() {
        if (form.chat.baseUrl.isBlank() || form.chat.apiKey.isBlank()) return
        fetching = true
        fetchError = null
        scope.launch {
            try {
                val remote = graph.listModels(form.chat.baseUrl, form.chat.apiKey)
                val profiles = remote.map { ModelDefaults.profile(it) }
                form = form.copy(models = profiles)
                val firstChat = profiles.firstOrNull { it.chatCapable }
                val firstVision = profiles.firstOrNull { it.chatCapable && it.supportsVision }
                if (form.chat.model.isBlank() && firstChat != null) applyProfile(firstChat, ModelRole.CHAT)
                if (form.operator.model.isBlank()) {
                    // Prefer a vision model; if the provider has none, still let the user save and show the warning.
                    (firstVision ?: firstChat)?.let { applyProfile(it, ModelRole.OPERATOR) }
                }
            } catch (e: LlmException) {
                fetchError = FetchError(e.statusCode, e.message ?: e.javaClass.simpleName)
            } catch (e: Exception) {
                fetchError = FetchError(null, e.message ?: e.javaClass.simpleName)
            } finally {
                fetching = false
            }
        }
    }

    fun updateProfile(updated: ModelProfile) {
        form = form.copy(
            models = form.models.map { if (it.id == updated.id) updated else it },
            // Editing the selected model must change what will actually be saved for chat/operator.
            chat = if (form.chat.model == updated.id) form.chat.withProfile(updated) else form.chat,
            operator = if (form.operator.model == updated.id) form.operator.withProfile(updated) else form.operator,
        )
        saved = false
    }

    fun probe(role: ModelRole) {
        val model = (if (role == ModelRole.CHAT) form.chat else form.operator).toSettings() ?: run {
            val state = ModelTestState(result = ProbeResult.Failed(message = "invalid"), role = role)
            if (role == ModelRole.CHAT) chatTest = state else operatorTest = state
            return
        }
        val testing = ModelTestState(testing = true, role = role)
        if (role == ModelRole.CHAT) chatTest = testing else operatorTest = testing
        scope.launch {
            val result = graph.probeModel(model, role)
            val state = ModelTestState(result = result, role = role)
            if (role == ModelRole.CHAT) chatTest = state else operatorTest = state
        }
    }

    fun probeProfile(profile: ModelProfile) {
        val base = form.chat.toSettings() ?: return
        val model = base.copy(
            model = profile.id,
            temperature = profile.temperature,
            maxTokens = profile.maxTokens,
            supportsVision = profile.supportsVision,
        )
        testingModel = profile.id
        scope.launch {
            val result = graph.probeModel(model, if (profile.supportsVision) ModelRole.OPERATOR else ModelRole.CHAT)
            modelResults = modelResults + (profile.id to result)
            testingModel = null
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = DockSpace + 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(R.string.settings_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SettingsCard(title = stringResource(R.string.settings_provider)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_provider_label), modifier = Modifier.weight(1f))
                    Box {
                        OutlinedButton(onClick = { providerMenu = true }, shape = RoundedCornerShape(16.dp)) {
                            Text(stringResource(provider.nameRes))
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                            Providers.all.forEach { item ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(item.nameRes)) },
                                    onClick = {
                                        providerMenu = false
                                        changeProvider(item)
                                    },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = form.chat.baseUrl,
                    onValueChange = ::changeBaseUrl,
                    label = { Text(stringResource(R.string.settings_base_url)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(stringResource(provider.keyHintRes), style = MaterialTheme.typography.labelSmall) },
                )
                OutlinedTextField(
                    value = form.chat.apiKey,
                    onValueChange = ::changeKey,
                    label = { Text(stringResource(R.string.settings_api_key)) },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = stringResource(if (showKey) R.string.settings_hide_key else R.string.settings_show_key),
                            )
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = ::fetchModels,
                        enabled = form.chat.baseUrl.isNotBlank() && form.chat.apiKey.isNotBlank() && !fetching,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        if (fetching) {
                            CircularProgressIndicator(Modifier.padding(end = 8.dp).size(14.dp), strokeWidth = 2.dp)
                        }
                        Text(stringResource(if (fetching) R.string.settings_fetching else R.string.settings_fetch_models))
                    }
                    if (form.models.isNotEmpty()) {
                        Text(
                            stringResource(R.string.settings_models_count, form.models.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                fetchError?.let { error ->
                    Text(
                        fetchErrorText(error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (error.statusCode == 404 || error.statusCode == 400) {
                        Text(
                            stringResource(R.string.settings_models_unsupported),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.settings_models_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { manualAdd = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.settings_manual_add), modifier = Modifier.padding(start = 4.dp))
                }
            }
        }

        if (form.models.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.settings_models_empty),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(form.models, key = { it.id }) { profile ->
                val chatSelected = form.chat.model == profile.id
                val operatorSelected = form.operator.model == profile.id
                ModelRow(
                    profile = profile,
                    expanded = expanded == profile.id,
                    chatSelected = chatSelected,
                    operatorSelected = operatorSelected,
                    testing = testingModel == profile.id,
                    testResult = modelResults[profile.id],
                    onToggle = { expanded = if (expanded == profile.id) null else profile.id },
                    onChange = ::updateProfile,
                    onUseChat = { applyProfile(profile, ModelRole.CHAT) },
                    onUseOperator = { applyProfile(profile, ModelRole.OPERATOR) },
                    onTest = { probeProfile(profile) },
                )
            }
        }

        item {
            SettingsCard(title = stringResource(R.string.settings_test_title)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { probe(ModelRole.CHAT) }, enabled = form.chat.toSettings() != null && !chatTest.testing, shape = RoundedCornerShape(16.dp)) {
                        if (chatTest.testing) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(14.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.settings_test_chat))
                    }
                    chatTest.result?.let { TestResultText(it, chatTest.role) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { probe(ModelRole.OPERATOR) }, enabled = form.operator.toSettings() != null && !operatorTest.testing, shape = RoundedCornerShape(16.dp)) {
                        if (operatorTest.testing) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(14.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.settings_test_operator))
                    }
                    operatorTest.result?.let { TestResultText(it, operatorTest.role) }
                }
                if (form.operator.supportsVision.not()) {
                    Text(
                        stringResource(R.string.settings_operator_vision_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        item {
            SettingsCard(title = stringResource(R.string.settings_appearance)) {
                Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        AppSettings.THEME_SYSTEM to R.string.settings_theme_system,
                        AppSettings.THEME_LIGHT to R.string.settings_theme_light,
                        AppSettings.THEME_DARK to R.string.settings_theme_dark,
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = form.themeMode == mode,
                            onClick = {
                                form = form.copy(themeMode = mode)
                                // Theme should preview immediately, not only after "Save all".
                                store.save(store.current.copy(themeMode = mode))
                                saved = false
                            },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
                Text(
                    stringResource(R.string.settings_language),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        AppSettings.LANGUAGE_SYSTEM to R.string.settings_language_system,
                        AppSettings.LANGUAGE_ZH to R.string.settings_language_zh,
                        AppSettings.LANGUAGE_EN to R.string.settings_language_en,
                    ).forEach { (language, label) ->
                        FilterChip(
                            selected = form.language == language,
                            onClick = {
                                val next = form.copy(language = language)
                                form = next
                                // Persist valid edits with the language so recreate() keeps them.
                                store.save(next.toSettings() ?: store.current.copy(language = language))
                                saved = false
                                context.activity()?.recreate()
                            },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
                Text(
                    stringResource(R.string.settings_language_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SettingsCard(title = stringResource(R.string.settings_voice)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.settings_voice_auto_send))
                        Text(
                            stringResource(R.string.settings_voice_auto_send_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = form.voiceAutoSend, onCheckedChange = {
                        form = form.copy(voiceAutoSend = it)
                        saved = false
                    })
                }
            }
        }

        item {
            SettingsCard(title = stringResource(R.string.settings_notifications)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.settings_notifications_enabled))
                        Text(
                            stringResource(R.string.settings_notifications_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = form.notificationsEnabled, onCheckedChange = {
                        form = form.copy(notificationsEnabled = it)
                        saved = false
                    })
                }
            }
        }

        item {
            SettingsCard(title = stringResource(R.string.settings_advanced)) {
                OutlinedTextField(
                    value = form.requestTimeout,
                    onValueChange = {
                        form = form.copy(requestTimeout = it)
                        saved = false
                    },
                    label = { Text(stringResource(R.string.settings_request_timeout)) },
                    isError = !form.requestTimeoutValid,
                    supportingText = {
                        Text(
                            if (form.requestTimeoutValid) {
                                stringResource(R.string.settings_request_timeout_hint)
                            } else {
                                stringResource(R.string.settings_invalid_number)
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.chatHistoryLimit,
                    onValueChange = {
                        form = form.copy(chatHistoryLimit = it)
                        saved = false
                    },
                    label = { Text(stringResource(R.string.settings_history_limit)) },
                    isError = !form.historyLimitValid,
                    supportingText = {
                        Text(
                            if (form.historyLimitValid) {
                                stringResource(R.string.settings_history_limit_hint)
                            } else {
                                stringResource(R.string.settings_invalid_number)
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                        shape = RoundedCornerShape(14.dp),
                    ) { Text(stringResource(R.string.settings_open_accessibility)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clearTarget = "chat" }, shape = RoundedCornerShape(14.dp)) {
                        Text(stringResource(R.string.settings_clear_chat))
                    }
                    OutlinedButton(onClick = { clearTarget = "traces" }, shape = RoundedCornerShape(14.dp)) {
                        Text(stringResource(R.string.settings_clear_traces))
                    }
                }
            }
        }

        item {
            SettingsCard(title = stringResource(R.string.settings_agent)) {
                OutlinedTextField(
                    value = form.maxSteps,
                    onValueChange = {
                        form = form.copy(maxSteps = it)
                        saved = false
                    },
                    label = { Text(stringResource(R.string.settings_max_steps)) },
                    isError = !form.maxStepsValid,
                    supportingText = { if (!form.maxStepsValid) Text(stringResource(R.string.settings_invalid_number)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_learn), modifier = Modifier.weight(1f))
                    Switch(checked = form.learnFromSuccess, onCheckedChange = {
                        form = form.copy(learnFromSuccess = it)
                        saved = false
                    })
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = parsed != null, onClick = {
                    parsed?.let {
                        store.save(it)
                        saved = true
                    }
                }, shape = RoundedCornerShape(18.dp)) {
                    Text(stringResource(R.string.settings_save_all))
                }
                if (saved) {
                    Text(
                        stringResource(R.string.settings_saved_models, form.models.size),
                        modifier = Modifier.padding(start = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        item {
            SettingsCard(title = stringResource(R.string.settings_folders)) {
                FolderLine(stringResource(R.string.settings_folder_skills_label), graph.skillsDir.path)
                FolderLine(stringResource(R.string.settings_folder_memory_label), graph.memoryDir.path)
                FolderLine(stringResource(R.string.settings_folder_traces_label), graph.tracesDir.path)
                FolderLine(stringResource(R.string.settings_folder_chat_label), graph.externalRoot.resolve("chat").path)
            }
        }

        item {
            SettingsCard(title = stringResource(R.string.settings_about)) {
                Text(
                    stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.settings_about_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (manualAdd) {
        ManualModelDialog(
            onDismiss = { manualAdd = false },
            onAdd = { id ->
                val profile = ModelDefaults.profile(id)
                form = form.copy(models = (form.models + profile).distinctBy { it.id })
                expanded = profile.id
                manualAdd = false
            },
        )
    }

    clearTarget?.let { target ->
        val chat = target == "chat"
        AlertDialog(
            onDismissRequest = { clearTarget = null },
            title = {
                Text(
                    stringResource(if (chat) R.string.settings_clear_chat_title else R.string.settings_clear_traces_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            text = {
                Text(stringResource(if (chat) R.string.settings_clear_chat_message else R.string.settings_clear_traces_message))
            },
            confirmButton = {
                TextButton(onClick = {
                    clearTarget = null
                    if (chat) {
                        graph.session.clearHistory()
                    } else {
                        scope.launch(Dispatchers.IO) {
                            graph.tracesDir.listFiles()?.forEach { it.deleteRecursively() }
                        }
                    }
                }) {
                    Text(
                        stringResource(if (chat) R.string.settings_clear_chat_confirm else R.string.settings_clear_traces_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = { TextButton(onClick = { clearTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ModelRow(
    profile: ModelProfile,
    expanded: Boolean,
    chatSelected: Boolean,
    operatorSelected: Boolean,
    testing: Boolean,
    testResult: ProbeResult?,
    onToggle: () -> Unit,
    onChange: (ModelProfile) -> Unit,
    onUseChat: () -> Unit,
    onUseOperator: () -> Unit,
    onTest: () -> Unit,
) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        profile.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (chatSelected) MiniBadge(stringResource(R.string.settings_model_chat_selected), selected = true)
                        if (operatorSelected) MiniBadge(stringResource(R.string.settings_model_operator_selected), selected = true)
                        if (profile.supportsVision) MiniBadge(stringResource(R.string.settings_model_vision))
                        if (!profile.chatCapable) MiniBadge(stringResource(R.string.settings_model_non_chat), error = true)
                    }
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(if (expanded) R.string.chat_process_collapse else R.string.chat_process_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    OutlinedTextField(
                        value = profile.displayName,
                        onValueChange = { onChange(profile.copy(displayName = it)) },
                        label = { Text(stringResource(R.string.settings_model_name_label)) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = profile.maxTokens?.toString().orEmpty(),
                            onValueChange = { onChange(profile.copy(maxTokens = it.trim().toIntOrNull())) },
                            label = { Text(stringResource(R.string.settings_model_max_tokens)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = profile.contextWindow?.toString().orEmpty(),
                            onValueChange = { onChange(profile.copy(contextWindow = it.trim().toIntOrNull())) },
                            label = { Text(stringResource(R.string.settings_model_context)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    OutlinedTextField(
                        value = profile.temperature?.toString().orEmpty(),
                        onValueChange = { onChange(profile.copy(temperature = it.trim().toDoubleOrNull())) },
                        label = { Text(stringResource(R.string.settings_temperature)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.settings_model_vision), modifier = Modifier.weight(1f))
                        Switch(checked = profile.supportsVision, onCheckedChange = { onChange(profile.copy(supportsVision = it)) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = onUseChat, enabled = profile.chatCapable, shape = RoundedCornerShape(14.dp)) {
                            Text(stringResource(R.string.settings_model_use_chat))
                        }
                        OutlinedButton(onClick = onUseOperator, enabled = profile.chatCapable, shape = RoundedCornerShape(14.dp)) {
                            Text(stringResource(R.string.settings_model_use_operator))
                        }
                        TextButton(onClick = onTest, enabled = profile.chatCapable && !testing) {
                            if (testing) CircularProgressIndicator(Modifier.padding(end = 6.dp).size(12.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.settings_model_test))
                        }
                    }
                    if (operatorSelected && !profile.supportsVision) {
                        Text(
                            stringResource(R.string.settings_operator_vision_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    testResult?.let { TestResultText(it, if (profile.supportsVision) ModelRole.OPERATOR else ModelRole.CHAT) }
                }
            }
        }
    }
}

@Composable
private fun MiniBadge(text: String, selected: Boolean = false, error: Boolean = false) {
    val background = when {
        error -> MaterialTheme.colorScheme.errorContainer
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val foreground = when {
        error -> MaterialTheme.colorScheme.onErrorContainer
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = CircleShape, color = background) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = foreground,
        )
    }
}

@Composable
private fun ManualModelDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var id by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_manual_add_title), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text(stringResource(R.string.settings_model_id)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.settings_manual_add_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(id.trim()) }, enabled = id.isNotBlank()) {
                Text(stringResource(R.string.add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
        }
    }
}

@Composable
private fun TestResultText(result: ProbeResult, role: ModelRole?) {
    when (result) {
        is ProbeResult.Ok -> Text(
            stringResource(R.string.settings_test_ok, result.latencyMillis),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        is ProbeResult.Failed -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                diagnosis(result, role),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            val detail = result.message.takeIf { it.isNotBlank() && it != "invalid" }
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun diagnosis(result: ProbeResult.Failed, role: ModelRole?): String = when {
    result.message == "invalid" -> stringResource(R.string.settings_test_invalid)
    result.statusCode == 401 || result.statusCode == 403 -> stringResource(R.string.settings_test_auth)
    result.statusCode == 404 -> stringResource(R.string.settings_test_not_found)
    result.statusCode == 429 || result.providerCode == "SetLimitExceeded" -> stringResource(R.string.settings_test_limit)
    result.statusCode == 400 && role == ModelRole.OPERATOR -> stringResource(R.string.settings_test_vision)
    result.message.contains("timeout", ignoreCase = true) ||
        result.message.contains("failed", ignoreCase = true) ||
        result.message.contains("Unable to resolve host", ignoreCase = true) -> stringResource(R.string.settings_test_network)
    else -> stringResource(
        R.string.settings_test_failed,
        listOfNotNull(result.statusCode?.toString(), result.providerCode).joinToString(" / ").ifEmpty { "?" },
    )
}

@Composable
private fun fetchErrorText(error: FetchError): String = when {
    error.statusCode == 401 || error.statusCode == 403 -> stringResource(R.string.settings_test_auth)
    error.statusCode == 404 -> stringResource(R.string.settings_models_endpoint_missing)
    error.statusCode == 429 -> stringResource(R.string.settings_test_limit)
    error.message.contains("timeout", ignoreCase = true) ||
        error.message.contains("failed", ignoreCase = true) -> stringResource(R.string.settings_test_network)
    else -> stringResource(R.string.settings_fetch_failed, error.message.take(160))
}

private fun SettingsForm.ModelForm.withProfile(profile: ModelProfile) = copy(
    temperature = profile.temperature?.toString().orEmpty(),
    maxTokens = profile.maxTokens?.toString().orEmpty(),
    contextWindow = profile.contextWindow?.toString().orEmpty(),
    supportsVision = profile.supportsVision,
    displayName = profile.title,
)

/** Compose's LocalContext may be a theme wrapper; walk up to the hosting Activity. */
private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
private fun FolderLine(label: String, path: String) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(path, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
