package dev.xiaoman.app.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists [AppSettings]: everything but the API keys in [plain], the keys only in [secret]
 * (EncryptedSharedPreferences in the app). Nothing here logs a key.
 */
class SettingsStore(private val plain: SharedPreferences, internal val secret: SharedPreferences) {

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    @Synchronized
    fun save(settings: AppSettings) {
        plain.edit(commit = true) {
            putModel(CHAT, settings.chat)
            putModel(OPERATOR, settings.operator)
            putInt(MAX_STEPS, settings.maxSteps)
            putBoolean(LEARN, settings.learnFromSuccess)
            putString(PROVIDER_ID, settings.providerId)
            putString(MODELS, json.encodeToString(settings.models))
            putString(THEME, settings.themeMode)
            putBoolean(VOICE_AUTO_SEND, settings.voiceAutoSend)
            putInt(REQUEST_TIMEOUT, settings.requestTimeoutSeconds)
        }
        secret.edit(commit = true) {
            putString(CHAT + API_KEY, settings.chat.apiKey)
            putString(OPERATOR + API_KEY, settings.operator.apiKey)
        }
        _settings.value = settings
    }

    private fun load(): AppSettings {
        val defaults = AppSettings()
        val timeout = plain.getInt(REQUEST_TIMEOUT, defaults.requestTimeoutSeconds)
        val chat = model(CHAT, timeout)
        val operator = model(OPERATOR, timeout)
        val providerId = plain.getString(PROVIDER_ID, null) ?: Providers.detect(chat.baseUrl).id
        val rawModels = plain.getString(MODELS, null)
        val saved = rawModels?.let {
            runCatching { json.decodeFromString<List<ModelProfile>>(it) }.getOrDefault(emptyList())
        }.orEmpty()
        return AppSettings(
            chat = chat,
            operator = operator,
            maxSteps = plain.getInt(MAX_STEPS, defaults.maxSteps),
            learnFromSuccess = plain.getBoolean(LEARN, defaults.learnFromSuccess),
            providerId = providerId,
            // Old installs have no catalog key; seed it from the two active models. A saved empty list stays empty.
            models = if (rawModels == null) seedProfiles(chat, operator) else saved,
            themeMode = plain.getString(THEME, null) ?: defaults.themeMode,
            voiceAutoSend = plain.getBoolean(VOICE_AUTO_SEND, defaults.voiceAutoSend),
            requestTimeoutSeconds = timeout,
        )
    }

    private fun seedProfiles(chat: ModelSettings, operator: ModelSettings): List<ModelProfile> = buildList {
        if (chat.model.isNotBlank()) {
            add(
                ModelProfile(
                    id = chat.model,
                    displayName = chat.displayName.ifBlank { chat.model },
                    maxTokens = chat.maxTokens,
                    temperature = chat.temperature,
                    contextWindow = chat.contextWindow,
                    supportsVision = chat.supportsVision,
                ),
            )
        }
        if (operator.model.isNotBlank() && operator.model != chat.model) {
            add(
                ModelProfile(
                    id = operator.model,
                    displayName = operator.displayName.ifBlank { operator.model },
                    maxTokens = operator.maxTokens,
                    temperature = operator.temperature,
                    contextWindow = operator.contextWindow,
                    supportsVision = operator.supportsVision,
                ),
            )
        }
    }

    private fun model(prefix: String, timeoutSeconds: Int) = ModelSettings(
        baseUrl = plain.getString(prefix + BASE_URL, null) ?: ModelSettings.DEFAULT_BASE_URL,
        model = plain.getString(prefix + MODEL, null).orEmpty(),
        apiKey = secret.getString(prefix + API_KEY, null).orEmpty(),
        temperature = plain.getString(prefix + TEMPERATURE, null)?.toDoubleOrNull(),
        maxTokens = plain.getString(prefix + MAX_TOKENS, null)?.toIntOrNull(),
        contextWindow = plain.getString(prefix + CONTEXT_WINDOW, null)?.toIntOrNull(),
        supportsVision = plain.getBoolean(prefix + SUPPORTS_VISION, false),
        displayName = plain.getString(prefix + DISPLAY_NAME, null).orEmpty(),
        timeoutSeconds = timeoutSeconds.toLong(),
    )

    private fun SharedPreferences.Editor.putModel(prefix: String, m: ModelSettings) {
        putString(prefix + BASE_URL, m.baseUrl)
        putString(prefix + MODEL, m.model)
        putString(prefix + TEMPERATURE, m.temperature?.toString())
        putString(prefix + MAX_TOKENS, m.maxTokens?.toString())
        putString(prefix + CONTEXT_WINDOW, m.contextWindow?.toString())
        putBoolean(prefix + SUPPORTS_VISION, m.supportsVision)
        putString(prefix + DISPLAY_NAME, m.displayName)
    }

    companion object {
        private const val TAG = "Xiaoman"
        private const val PLAIN_FILE = "settings"
        private const val SECRET_FILE = "settings-secrets"
        private const val CHAT = "chat."
        private const val OPERATOR = "operator."
        private const val BASE_URL = "baseUrl"
        private const val MODEL = "model"
        private const val API_KEY = "apiKey"
        private const val TEMPERATURE = "temperature"
        private const val MAX_TOKENS = "maxTokens"
        private const val CONTEXT_WINDOW = "contextWindow"
        private const val SUPPORTS_VISION = "supportsVision"
        private const val DISPLAY_NAME = "displayName"
        private const val PROVIDER_ID = "provider.id"
        private const val MODELS = "provider.models"
        private const val THEME = "appearance.theme"
        private const val VOICE_AUTO_SEND = "chat.voiceAutoSend"
        private const val REQUEST_TIMEOUT = "model.requestTimeout"
        private const val MAX_STEPS = "agent.maxSteps"
        private const val LEARN = "agent.learnFromSuccess"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun create(context: Context): SettingsStore =
            SettingsStore(context.getSharedPreferences(PLAIN_FILE, Context.MODE_PRIVATE), encrypted(context))

        private fun encrypted(context: Context): SharedPreferences = try {
            openEncrypted(context)
        } catch (e: Exception) {
            // A keystore entry lost after a restore or OS update makes the file undecryptable.
            // The keys cannot be recovered; start over so the user can type them again.
            Log.w(TAG, "settings: encrypted preferences unreadable (${e.javaClass.simpleName}); resetting API keys")
            context.deleteSharedPreferences(SECRET_FILE)
            openEncrypted(context)
        }

        private fun openEncrypted(context: Context): SharedPreferences {
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                context,
                SECRET_FILE,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
