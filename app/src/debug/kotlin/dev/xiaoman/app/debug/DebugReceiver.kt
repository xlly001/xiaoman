package dev.xiaoman.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import dev.xiaoman.api.OcrPurpose
import dev.xiaoman.api.TtsVoice
import dev.xiaoman.app.XiaomanApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug-build bridge for the `xiaoman` CLI:
 * `adb shell am broadcast -n dev.xiaoman.app/.debug.DebugReceiver -a dev.xiaoman.debug.RUN --es goal "打开设置" --es run_id r1`.
 *
 * RUN hands the task to the agent session (the foreground [dev.xiaoman.app.AgentService] is
 * started for it); approvals and questions still go to the user through the app. Every RUN ends
 * with a `Failed` or `PhoneTaskFinished` logcat line for its run id.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as XiaomanApp).graph
        val command = DebugCommand.parse(intent.action, intent::getStringExtra, graph.runIds::generate)
        when (command) {
            null -> Log.w(TAG, "debug: unknown action ${intent.action}")
            is DebugCommand.Run -> {
                Log.i(TAG, "debug: run ${command.runId} skill=${command.skill ?: "-"}")
                graph.session.runPhoneTask(command.goal, command.skill, command.runId)
            }
            is DebugCommand.InvalidRun -> {
                Log.w(TAG, "debug: run rejected: ${command.message}")
                graph.session.fail(command.runId, command.message)
            }
            DebugCommand.Stop -> {
                Log.i(TAG, "debug: stop")
                graph.session.stop()
            }
            is DebugCommand.Say -> {
                Log.i(TAG, "debug: say")
                android.os.Handler(android.os.Looper.getMainLooper()).post { Log.i(TAG, "debug: say -> ${graph.session.submit(command.text)}") }
            }
            is DebugCommand.Enqueue -> {
                val result = graph.session.enqueueTask(command.goal, command.skill)
                Log.i(TAG, "debug: enqueue ${command.goal} -> $result")
            }
            is DebugCommand.QueueControl -> {
                val store = graph.taskQueue
                val ok = when (command.op) {
                    DebugCommand.QueueOp.PAUSE -> store.pause(command.id)
                    DebugCommand.QueueOp.RESUME -> store.resume(command.id)
                    DebugCommand.QueueOp.CANCEL -> store.cancel(command.id)
                }
                Log.i(TAG, "debug: queue ${command.op.name.lowercase()} ${command.id} -> $ok")
            }
            DebugCommand.QueueStatus -> {
                val rows = graph.taskQueue.tasks.value
                Log.i(TAG, "debug: queue status ${rows.size} rows")
                rows.forEach { row ->
                    Log.i(TAG, "debug: queue row ${row.id} ${row.state} ${row.goal}")
                }
            }
            DebugCommand.MediaStatus -> {
                Log.i(TAG, "debug: media status ${graph.media.current().describe()}")
            }
            is DebugCommand.MediaAsr -> {
                val pending = goAsync()
                graph.scope.launch {
                    try {
                        val engine = graph.media.current().asr
                        if (command.autoStop) {
                            launch {
                                delay(command.millis)
                                engine.cancel()
                            }
                        }
                        val result = engine.listen()
                        Log.i(TAG, "debug: media asr engine=${engine.kind} text=${result.text} duration=${result.durationMs} request=${result.requestId}")
                    } catch (e: Exception) {
                        Log.w(TAG, "debug: media asr failed ${e.javaClass.simpleName}: ${e.message}")
                    } finally {
                        pending.finish()
                    }
                }
            }
            is DebugCommand.MediaTts -> {
                val pending = goAsync()
                graph.scope.launch {
                    try {
                        val engine = graph.media.current().tts
                        val fallback = graph.settings.current.ttsVoice
                        val voice = if (command.voice.isBlank()) fallback
                        else runCatching { TtsVoice.valueOf(command.voice.uppercase()) }.getOrDefault(fallback)
                        val result = engine.speak(command.text, voice)
                        Log.i(TAG, "debug: media tts engine=${engine.kind} bytes=${result.audioBytes} voice=${result.voice}")
                    } catch (e: Exception) {
                        Log.w(TAG, "debug: media tts failed ${e.javaClass.simpleName}: ${e.message}")
                    } finally {
                        pending.finish()
                    }
                }
            }
            is DebugCommand.MediaOcr -> {
                val pending = goAsync()
                graph.scope.launch {
                    try {
                        val engine = graph.media.current().ocr
                        val fallback = graph.settings.current.ocrPurpose
                        val purpose = if (command.purpose.isBlank()) fallback
                        else runCatching { OcrPurpose.valueOf(command.purpose.uppercase()) }.getOrDefault(fallback)
                        val result = engine.recognize(E2E_PNG, "e2e.png", purpose)
                        Log.i(TAG, "debug: media ocr engine=${engine.kind} pages=${result.pages.size} text=${result.text.take(120)}")
                    } catch (e: Exception) {
                        Log.w(TAG, "debug: media ocr failed ${e.javaClass.simpleName}: ${e.message}")
                    } finally {
                        pending.finish()
                    }
                }
            }
            DebugCommand.ReloadSkills -> {
                val pending = goAsync()
                graph.scope.launch {
                    try {
                        graph.startup.await()
                        val result = graph.skills.reload()
                        graph.logSkillProblems(result.skills.size, result.problems)
                    } catch (e: Exception) {
                        Log.w(TAG, "reload-skills: failed: ${e.message}")
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "Xiaoman"

        /** A real 1x1 PNG so the OCR multipart upload carries an actual image, not arbitrary bytes. */
        val E2E_PNG: ByteArray = Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
            Base64.DEFAULT,
        )
    }
}
