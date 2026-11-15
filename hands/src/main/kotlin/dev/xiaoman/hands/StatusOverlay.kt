package dev.xiaoman.hands

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * A small pill at the top of the screen showing the current step and a Stop button.
 *
 * [context] must be the connected [XiaomanAccessibilityService] (or a context derived from it):
 * the window is TYPE_ACCESSIBILITY_OVERLAY, which only an accessibility service may add and which
 * needs no SYSTEM_ALERT_WINDOW permission. The window wraps its content and is not focusable, so
 * touches outside the pill reach the app underneath.
 *
 * All methods may be called from any thread.
 */
class StatusOverlay(private val context: Context) {

    /** Invoked on the main thread when the user taps Stop. */
    var onStop: () -> Unit = {}
    var onOpen: () -> Unit = {}

    private val main = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var statusRow: LinearLayout? = null
    private var approvalCard: LinearLayout? = null
    private var textView: TextView? = null
    private var approvalTitle: TextView? = null
    private var approvalPrompt: TextView? = null
    private var denyButton: Button? = null
    private var onceButton: Button? = null
    private var taskButton: TextView? = null
    private var pendingText: CharSequence = ""
    private var mode = OverlayMode.STATUS
    private var denyAction: (() -> Unit)? = null
    private var onceAction: (() -> Unit)? = null
    private var taskAction: (() -> Unit)? = null

    private enum class OverlayMode { STATUS, APPROVAL }

    val isShowing: Boolean get() = root?.isAttachedToWindow == true

    fun show() = onMain {
        if (root != null) return@onMain
        val view = buildView()
        try {
            windowManager.addView(view, layoutParams())
            root = view
        } catch (e: RuntimeException) {
            // BadTokenException / WindowManager errors when the service is not connected.
            android.util.Log.w(TAG, "cannot show overlay: ${e.message}")
            root = null
            textView = null
        }
    }

    fun update(text: CharSequence) = onMain {
        pendingText = text
        if (mode == OverlayMode.STATUS) textView?.text = text
    }

    /** Back to the small step pill (also used when an approval is answered). */
    fun showStatus(text: CharSequence) = onMain {
        pendingText = text
        show()
        mode = OverlayMode.STATUS
        textView?.text = text
        approvalCard?.visibility = View.GONE
        statusRow?.apply {
            visibility = View.VISIBLE
            alpha = 0f
            animate().alpha(1f).setDuration(160).start()
        }
    }

    /**
     * Grows the pill into a confirmation card while Xiaoman operates another app. The window is
     * the accessibility overlay, so it is visible above the observed app without any extra
     * permission; the in-app approval island only helps when Xiaoman itself is in front.
     */
    fun showApproval(
        title: CharSequence,
        prompt: CharSequence,
        deny: CharSequence,
        once: CharSequence,
        task: CharSequence,
        onDeny: () -> Unit,
        onOnce: () -> Unit,
        onTask: () -> Unit,
    ) = onMain {
        show()
        mode = OverlayMode.APPROVAL
        denyAction = onDeny
        onceAction = onOnce
        taskAction = onTask
        approvalTitle?.text = title
        approvalPrompt?.text = prompt
        denyButton?.text = deny
        onceButton?.text = once
        taskButton?.text = task
        denyButton?.isEnabled = true
        onceButton?.isEnabled = true
        taskButton?.isEnabled = true
        statusRow?.visibility = View.GONE
        approvalCard?.apply {
            visibility = View.VISIBLE
            alpha = 0f
            animate().alpha(1f).setDuration(180).start()
        }
    }

    fun hide() = onMain {
        val view = root ?: return@onMain
        root = null
        statusRow = null
        approvalCard = null
        textView = null
        approvalTitle = null
        approvalPrompt = null
        denyButton = null
        onceButton = null
        taskButton = null
        mode = OverlayMode.STATUS
        runCatching { windowManager.removeViewImmediate(view) }
    }

    /**
     * Hides the overlay and disables its window's touch interception while [block] runs,
     * then restores both. View.GONE alone does not remove the WindowManager input surface.
     * Waits [settleMs] after hiding for the frame and input window update to be applied.
     */
    suspend fun <T> hiddenWhile(settleMs: Long = 80, block: suspend () -> T): T {
        val hidden = withContext(Dispatchers.Main.immediate) {
            val view = root
            if (view != null && view.visibility == View.VISIBLE) {
                val params = view.layoutParams as WindowManager.LayoutParams
                val flags = params.flags
                params.flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                windowManager.updateViewLayout(view, params)
                view.visibility = View.GONE
                view to flags
            } else null
        }
        if (hidden == null) return block()
        try {
            delay(settleMs)
            return block()
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                val (view, flags) = hidden
                // hide() clears root before removing the window. Identity is sufficient here;
                // attachment may lag while WindowManager is applying the layout update.
                if (root === view) {
                    val params = view.layoutParams as WindowManager.LayoutParams
                    params.flags = flags
                    view.visibility = View.VISIBLE
                    windowManager.updateViewLayout(view, params)
                }
            }
        }
    }

    private fun buildView(): LinearLayout {
        val pad = dp(10)
        val label = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = (context.resources.displayMetrics.widthPixels * 0.52f).toInt()
            text = pendingText
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = context.getString(R.string.xiaoman_overlay_open_description)
            setOnClickListener { onOpen() }
            // Read status changes to TalkBack users.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val stop = Button(context).apply {
            setText(R.string.xiaoman_overlay_stop)
            contentDescription = context.getString(R.string.xiaoman_overlay_stop_description)
            minimumHeight = dp(48)
            minHeight = dp(48)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setOnClickListener { onStop() }
        }
        val status = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad * 2, pad / 2, pad / 2, pad / 2)
            background = rounded(0xF01E4635.toInt(), 24)
            addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = pad })
            addView(stop, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val cardWidth = (context.resources.displayMetrics.widthPixels * 0.92f).toInt().coerceAtMost(dp(430))
        val title = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val prompt = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 5
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(8), 0, dp(10))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        }
        val deny = Button(context).apply {
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFFFFFFFF.toInt())
            backgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
            minHeight = dp(44)
            minimumHeight = dp(44)
            setOnClickListener { submit(denyAction) }
        }
        val once = Button(context).apply {
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF1E4635.toInt())
            backgroundTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            minHeight = dp(44)
            minimumHeight = dp(44)
            setOnClickListener { submit(onceAction) }
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(deny, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            addView(once, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val task = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(0x22FFFFFF, 16)
            setOnClickListener { submit(taskAction) }
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad * 2, pad * 2, pad * 2, pad)
            background = rounded(0xF01E4635.toInt(), 24)
            visibility = View.GONE
            addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(prompt, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(task, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }

        textView = label
        approvalTitle = title
        approvalPrompt = prompt
        denyButton = deny
        onceButton = once
        taskButton = task
        statusRow = status
        approvalCard = card

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(card, LinearLayout.LayoutParams(cardWidth, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    /** One answer per approval: disable the other actions before handing control back. */
    private fun submit(action: (() -> Unit)?) {
        if (action == null) return
        denyButton?.isEnabled = false
        onceButton?.isEnabled = false
        taskButton?.isEnabled = false
        action()
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dp(36)
        title = "Xiaoman status"
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private companion object {
        const val TAG = "Xiaoman"
    }
}
