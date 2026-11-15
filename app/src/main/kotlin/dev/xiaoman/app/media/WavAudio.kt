package dev.xiaoman.app.media

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal RIFF/WAVE codec. DeepSeek's `/v1/tools/asr` accepts 16-bit PCM WAV, and `/v1/tools/tts`
 * returns WAV, so both directions need a header-only implementation (no third-party dependency).
 */
object WavAudio {
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BITS = 16

    /** Wrap raw little-endian PCM in a canonical 44-byte WAV header. */
    fun encode(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE, channels: Int = CHANNELS, bits: Int = BITS): ByteArray {
        val bytesPerSample = bits / 8
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put(ascii("RIFF"))
        header.putInt(36 + pcm.size)
        header.put(ascii("WAVE"))
        header.put(ascii("fmt "))
        header.putInt(16)                                       // PCM fmt chunk length
        header.putShort(1)                                      // uncompressed PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * channels * bytesPerSample) // byte rate
        header.putShort((channels * bytesPerSample).toShort())
        header.putShort(bits.toShort())
        header.put(ascii("data"))
        header.putInt(pcm.size)
        return header.array() + pcm
    }

    data class Clip(val pcm: ByteArray, val sampleRate: Int, val channels: Int, val bits: Int) {
        /** Approximate playback length in milliseconds, used to let the AudioTrack tail drain. */
        val durationMs: Long
            get() {
                val bytesPerSecond = sampleRate * channels * (bits / 8)
                return if (bytesPerSecond <= 0) 0L else pcm.size * 1000L / bytesPerSecond
            }
    }

    /** Locate the `fmt ` and `data` chunks. Chunk order and extra metadata chunks are tolerated. */
    fun parse(wav: ByteArray): Clip {
        require(wav.size >= 44 && wav.ascii(0, 4) == "RIFF" && wav.ascii(8, 4) == "WAVE") {
            "not a RIFF/WAVE stream (${wav.size} bytes)"
        }
        var sampleRate = SAMPLE_RATE
        var channels = CHANNELS
        var bits = BITS
        var dataOffset = -1
        var dataSize = 0
        var pos = 12
        while (pos + 8 <= wav.size) {
            val id = wav.ascii(pos, 4)
            val size = wav.leInt(pos + 4)
            val start = pos + 8
            when {
                id == "fmt " && start + 16 <= wav.size -> {
                    channels = wav.leShort(start + 2).coerceAtLeast(1)
                    sampleRate = wav.leInt(start + 4).takeIf { it > 0 } ?: SAMPLE_RATE
                    bits = wav.leShort(start + 14).takeIf { it > 0 } ?: BITS
                }
                id == "data" -> {
                    dataOffset = start
                    dataSize = size.coerceAtMost(wav.size - start)
                    break
                }
            }
            if (size <= 0) break
            pos = start + size + (size and 1) // RIFF chunks are word-aligned
        }
        require(dataOffset >= 0 && dataSize > 0) { "WAV stream has no audio data" }
        return Clip(wav.copyOfRange(dataOffset, dataOffset + dataSize), sampleRate, channels, bits)
    }

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    private fun ByteArray.ascii(offset: Int, length: Int): String =
        String(this, offset, length, Charsets.US_ASCII)

    private fun ByteArray.leInt(offset: Int): Int {
        require(offset + 4 <= size)
        return (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)
    }

    private fun ByteArray.leShort(offset: Int): Int {
        require(offset + 2 <= size)
        return (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)
    }
}
