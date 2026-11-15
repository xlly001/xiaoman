package dev.xiaoman.app.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import dev.xiaoman.api.MediaCapability
import dev.xiaoman.api.MediaException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * Records a single 16 kHz / 16-bit / mono WAV clip for `/v1/tools/asr` (120 s and 8 MiB limit).
 * [finish] ends the capture but keeps what was recorded, so the mic button works as start/stop.
 */
class WavRecorder(private val context: Context) {
    @Volatile private var recording = false

    @Volatile private var finishRequested = false

    val isRecording: Boolean get() = recording

    suspend fun record(maxSeconds: Int = 120): ByteArray = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw MediaException("需要录音权限才能使用语音识别", capability = MediaCapability.ASR)
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            WavAudio.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            throw MediaException("设备不支持 16 kHz PCM 录音", capability = MediaCapability.ASR)
        }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            WavAudio.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 2,
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw MediaException("麦克风初始化失败，可能正被其他应用占用", capability = MediaCapability.ASR)
        }

        val pcm = ByteArrayOutputStream()
        val shorts = ShortArray(minBuffer / 2)
        val bytes = ByteArray(shorts.size * 2)
        val deadline = System.currentTimeMillis() + maxSeconds * 1000L
        recording = true
        finishRequested = false
        try {
            recorder.startRecording()
            while (!finishRequested && isActive && System.currentTimeMillis() < deadline && pcm.size() < MAX_PCM_BYTES) {
                val read = recorder.read(shorts, 0, shorts.size)
                if (read <= 0) continue
                var out = 0
                for (i in 0 until read) {
                    val value = shorts[i].toInt()
                    bytes[out++] = (value and 0xff).toByte()
                    bytes[out++] = ((value shr 8) and 0xff).toByte()
                }
                pcm.write(bytes, 0, out)
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            recording = false
        }
        val raw = pcm.toByteArray()
        if (raw.size < MIN_PCM_BYTES) {
            throw MediaException("录音太短，请说完后再点停止", capability = MediaCapability.ASR)
        }
        WavAudio.encode(raw)
    }

    /** Stop capture and let [record] return the clip captured so far. */
    fun finish() {
        finishRequested = true
    }

    companion object {
        /** 8 MiB is the ASR upload cap; leave room for the 44-byte WAV header. */
        const val MAX_PCM_BYTES = 8 * 1024 * 1024 - 44
        const val MIN_PCM_BYTES = 1_600 // ~50 ms at 16 kHz mono
    }
}

/** Plays the WAV returned by `/v1/tools/tts` through AudioTrack, with an interruptible write loop. */
class WavPlayer {
    @Volatile private var track: AudioTrack? = null

    @Volatile private var interrupted = false

    /** @return the number of PCM bytes written. */
    suspend fun play(wav: ByteArray): Int = withContext(Dispatchers.IO) {
        val clip = try {
            WavAudio.parse(wav)
        } catch (e: IllegalArgumentException) {
            throw MediaException("TTS 返回的音频无法解析：${e.message}", capability = MediaCapability.TTS, cause = e)
        }
        val channelMask = if (clip.channels <= 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val encoding = when (clip.bits) {
            8 -> AudioFormat.ENCODING_PCM_8BIT
            16 -> AudioFormat.ENCODING_PCM_16BIT
            else -> AudioFormat.ENCODING_PCM_FLOAT
        }
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT || clip.bits !in setOf(8, 16)) {
            throw MediaException("不支持的音频位深：${clip.bits}", capability = MediaCapability.TTS)
        }
        val minBuffer = AudioTrack.getMinBufferSize(clip.sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            throw MediaException("设备不支持播放 ${clip.sampleRate} Hz 音频", capability = MediaCapability.TTS)
        }
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(clip.sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, clip.pcm.size))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = audioTrack
        interrupted = false
        var writtenTotal = 0
        try {
            audioTrack.play()
            val chunk = maxOf(4096, minBuffer)
            while (writtenTotal < clip.pcm.size && !interrupted && isActive) {
                val count = min(chunk, clip.pcm.size - writtenTotal)
                val written = audioTrack.write(clip.pcm, writtenTotal, count, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                writtenTotal += written
            }
            if (!interrupted) {
                // WRITE_BLOCKING only guarantees the data reached the buffer; let the tail play out.
                delay((clip.durationMs + 80).coerceAtMost(30_000))
            }
        } finally {
            runCatching { audioTrack.stop() }
            audioTrack.release()
            track = null
        }
        writtenTotal
    }

    fun stop() {
        interrupted = true
        runCatching {
            track?.pause()
            track?.flush()
        }
    }
}
