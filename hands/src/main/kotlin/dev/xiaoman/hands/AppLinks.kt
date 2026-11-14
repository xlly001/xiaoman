package dev.xiaoman.hands

import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock

/**
 * Links that Xiaoman itself turns into system intents, so a skill can reach a stable system entry
 * point through the ordinary `deeplink` action (and its Sentinel check) instead of fiddly screens.
 *
 * `xiaoman://alarm/set?hour=14&minute=0&label=喝水` opens the clock's new-alarm page prefilled
 * (ACTION_SET_ALARM, never skipping the UI): the operator still checks the page and taps save.
 */
internal object AppLinks {
    const val SCHEME = "xiaoman"
    private const val MAX_LABEL = 60

    /** The intent for [uri]; null when it is not a Xiaoman link. Throws [IllegalArgumentException] for a malformed one. */
    fun intentFor(uri: Uri): Intent? {
        if (uri.scheme?.lowercase() != SCHEME) return null
        return when ("${uri.host.orEmpty()}${uri.path.orEmpty()}".trimEnd('/')) {
            "alarm/set" -> setAlarm(uri)
            else -> throw IllegalArgumentException("不支持的小满链接：$uri（目前只支持 xiaoman://alarm/set）")
        }
    }

    private fun setAlarm(uri: Uri): Intent {
        val hour = uri.getQueryParameter("hour")?.trim()?.toIntOrNull()?.takeIf { it in 0..23 }
            ?: throw IllegalArgumentException("xiaoman://alarm/set 需要 hour=0..23")
        val minute = (uri.getQueryParameter("minute")?.trim()?.ifEmpty { null } ?: "0").toIntOrNull()?.takeIf { it in 0..59 }
            ?: throw IllegalArgumentException("xiaoman://alarm/set 的 minute 需要 0..59")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        uri.getQueryParameter("label")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it.take(MAX_LABEL)) }
        return intent
    }
}
