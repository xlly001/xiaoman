package dev.xiaoman.app

import android.content.Context
import android.content.Intent
import dev.xiaoman.api.ApprovalAnswer
import dev.xiaoman.api.ApprovalScope
import dev.xiaoman.app.session.AgentSession
import dev.xiaoman.app.session.PendingApproval
import dev.xiaoman.hands.AccessibilityDevice
import dev.xiaoman.hands.XiaomanAccessibilityService
import dev.xiaoman.hands.StatusOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Shows the hands' [StatusOverlay] (step text + Stop) while the session is busy and the
 * accessibility service is connected; the overlay must be created with the connected service.
 *
 * The same overlay is also the only confirmation surface that can reach the user while Xiaoman
 * operates another app and the app itself is in the background: when [AgentSession.approval]
 * becomes non-null the pill grows into an approval card with deny / once / whole-task actions.
 * The in-app island handles the foreground case.
 */
class OverlayController(
    private val context: Context,
    private val session: AgentSession,
    private val device: AccessibilityDevice,
    private val services: StateFlow<XiaomanAccessibilityService?> = XiaomanAccessibilityService.instance,
    private val uiVisible: StateFlow<Boolean> = MutableStateFlow(false),
    /** Shown while a task is busy but has no step text yet; follows the app language. */
    private val fallbackTitle: String = "小满",
) {
    private var overlay: StatusOverlay? = null
    private var owner: XiaomanAccessibilityService? = null

    private data class OverlayState(
        val service: XiaomanAccessibilityService?,
        val visible: Boolean,
        val status: String?,
        val approval: PendingApproval?,
    )

    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.Main.immediate) {
        var showingApproval = false
        var shownApprovalId: Long? = null
        combine(services, session.busy, session.status, uiVisible, session.approval) { service, busy, status, visible, approval ->
            OverlayState(service, busy && !visible, status, approval)
        }.collect { state ->
            val service = state.service
            if (!state.visible || service == null) {
                remove()
                showingApproval = false
                shownApprovalId = null
                return@collect
            }
            if (owner !== service) {
                remove()
                overlay = StatusOverlay(service).also {
                    it.onStop = { session.stop() }
                    it.onOpen = {
                        service.startActivity(
                            Intent(service, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        )
                    }
                    device.overlay = it
                    it.show()
                }
                owner = service
                showingApproval = false
                shownApprovalId = null
            }

            val approval = state.approval
            if (approval != null) {
                if (!showingApproval || shownApprovalId != approval.id) {
                    overlay?.showApproval(
                        title = context.getString(R.string.approval_title),
                        prompt = approval.request.prompt,
                        deny = context.getString(R.string.approval_deny),
                        once = context.getString(R.string.approval_once),
                        task = context.getString(R.string.approval_task),
                        onDeny = { session.answerApproval(ApprovalAnswer(false), approval.id) },
                        onOnce = { session.answerApproval(ApprovalAnswer(true, ApprovalScope.ONCE), approval.id) },
                        onTask = { session.answerApproval(ApprovalAnswer(true, ApprovalScope.TASK), approval.id) },
                    )
                    showingApproval = true
                    shownApprovalId = approval.id
                }
            } else if (showingApproval) {
                showingApproval = false
                shownApprovalId = null
                overlay?.showStatus(state.status ?: fallbackTitle)
            } else {
                overlay?.update(state.status ?: fallbackTitle)
            }
        }
    }

    private fun remove() {
        overlay?.hide()
        overlay = null
        owner = null
        device.overlay = null
    }
}
