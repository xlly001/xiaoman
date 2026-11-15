package dev.xiaoman.app

import android.content.Context
import android.content.res.Configuration
import dev.xiaoman.app.settings.AppSettings
import java.util.Locale

/**
 * App-level language override (system / zh / en).
 *
 * The preference is read straight from the plain settings file so [MainActivity.attachBaseContext]
 * can apply it before any Compose content is created, without touching the encrypted settings store.
 */
object AppLocale {
    private const val PREFS = "settings"
    private const val KEY = "appearance.language"

    fun stored(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.takeIf { it in SUPPORTED }
            ?: AppSettings.LANGUAGE_SYSTEM

    /** Returns a context whose resources use the selected app language. */
    fun wrap(base: Context): Context {
        val language = stored(base)
        if (language == AppSettings.LANGUAGE_SYSTEM) return base
        val locale = Locale.forLanguageTag(language)
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return base.createConfigurationContext(configuration)
    }

    private val SUPPORTED = setOf(
        AppSettings.LANGUAGE_SYSTEM,
        AppSettings.LANGUAGE_ZH,
        AppSettings.LANGUAGE_EN,
    )
}
