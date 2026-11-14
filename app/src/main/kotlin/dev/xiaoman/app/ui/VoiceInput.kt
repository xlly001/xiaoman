package dev.xiaoman.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.xiaoman.app.R
import java.util.Locale

/** State and actions of the system speech recognizer used by the chat composer. */
class VoiceInput(
    val listening: Boolean,
    val errorRes: Int?,
    val start: () -> Unit,
    val stop: () -> Unit,
    val clearError: () -> Unit,
)

/**
 * Wraps [SpeechRecognizer] with the record-audio permission and maps failures to string resources.
 *
 * Only the final transcript is delivered; partial results would fight the user's own typing.
 */
@Composable
fun rememberVoiceInput(onResult: (String) -> Unit): VoiceInput {
    val context = LocalContext.current
    val latestResult = rememberUpdatedState(onResult)
    var listening by remember { mutableStateOf(false) }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    var pendingStart by remember { mutableStateOf(false) }

    val recognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        } else {
            null
        }
    }

    val listener = remember(recognizer) {
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listening = true
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                listening = false
            }

            override fun onError(error: Int) {
                listening = false
                errorRes = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> R.string.voice_no_match
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.voice_timeout
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_permission_denied
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.voice_busy
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> R.string.voice_network
                    else -> R.string.voice_error
                }
            }

            override fun onResults(results: Bundle?) {
                listening = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (text.isNotEmpty()) latestResult.value(text) else errorRes = R.string.voice_no_match
            }

            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }
    }

    DisposableEffect(recognizer, listener) {
        recognizer?.setRecognitionListener(listener)
        onDispose { recognizer?.destroy() }
    }

    fun startRecognizer() {
        if (recognizer == null) {
            errorRes = R.string.voice_unavailable
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        errorRes = null
        listening = true
        runCatching { recognizer.startListening(intent) }.onFailure {
            listening = false
            errorRes = R.string.voice_error
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            pendingStart = true
        } else {
            pendingStart = false
            errorRes = R.string.voice_permission_denied
        }
    }

    LaunchedEffect(pendingStart, recognizer) {
        if (pendingStart) {
            pendingStart = false
            startRecognizer()
        }
    }

    fun start() {
        errorRes = null
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) startRecognizer() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    return VoiceInput(
        listening = listening,
        errorRes = errorRes,
        start = ::start,
        stop = {
            listening = false
            recognizer?.stopListening()
        },
        clearError = { errorRes = null },
    )
}
