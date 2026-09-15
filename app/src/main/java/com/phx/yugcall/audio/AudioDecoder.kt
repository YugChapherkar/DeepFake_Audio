package com.phx.yugcall.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes an arbitrary audio file to the 16 kHz mono float PCM the model needs.
 *
 * Replaces the previous upload path, which accepted a URI and then discarded it
 * without reading a single sample.
 */
class AudioDecoder(private val context: Context) {

    /**
     * @param maxSeconds hard cap so a long recording cannot exhaust the heap.
     *   At 16 kHz mono float, 120 s is ~7.7 MB.
     */
    suspend fun decodeToMono16k(uri: Uri, maxSeconds: Int = 120): FloatArray =
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                val track = (0 until extractor.trackCount).firstOrNull { i ->
                    extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                } ?: throw IllegalArgumentException("No audio track in the selected file")

                val format = extractor.getTrackFormat(track)
                extractor.selectTrack(track)
                decodeTrack(extractor, format, maxSeconds)
            } finally {
                extractor.release()
            }
        }

    private suspend fun decodeTrack(
        extractor: MediaExtractor,
        inputFormat: MediaFormat,
        maxSeconds: Int,
    ): FloatArray {
        val mime = requireNotNull(inputFormat.getString(MediaFormat.KEY_MIME))
        val codec = MediaCodec.createDecoderByType(mime)
        val pcm = GrowableFloats()

        // Overwritten from the output format, which is authoritative — the input
        // format does not always carry the real values.
        var sampleRate = inputFormat.optInt(MediaFormat.KEY_SAMPLE_RATE, AudioFormatSpec.SAMPLE_RATE)
        var channels = inputFormat.optInt(MediaFormat.KEY_CHANNEL_COUNT, 1)
        var pcmEncoding = inputFormat.optInt(MediaFormat.KEY_PCM_ENCODING, ENCODING_PCM_16BIT)

        try {
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false

            while (!sawOutputEos) {
                currentCoroutineContext().ensureActive()

                if (!sawInputEos) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        sampleRate = out.optInt(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = out.optInt(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        pcmEncoding = out.optInt(MediaFormat.KEY_PCM_ENCODING, pcmEncoding)
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (index >= 0) {
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(index)!!
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            appendDownmixed(buffer, pcmEncoding, channels, pcm)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEos = true
                        }
                        if (pcm.size >= sampleRate * maxSeconds) sawOutputEos = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }

        val mono = pcm.toArray()
        require(mono.isNotEmpty()) { "Decoded no audio from the selected file" }
        return resampleTo16k(mono, sampleRate)
    }

    private fun appendDownmixed(
        buffer: ByteBuffer,
        pcmEncoding: Int,
        channels: Int,
        sink: GrowableFloats,
    ) {
        val ordered = buffer.order(ByteOrder.nativeOrder())
        when (pcmEncoding) {
            ENCODING_PCM_FLOAT -> {
                val floats = ordered.asFloatBuffer()
                downmix(floats.remaining(), channels) { i -> floats.get(i) }.forEach(sink::add)
            }
            else -> {
                val shorts = ordered.asShortBuffer()
                downmix(shorts.remaining(), channels) { i -> shorts.get(i) / PCM16_FULL_SCALE }
                    .forEach(sink::add)
            }
        }
    }

    /** Averages interleaved channels down to mono. */
    private inline fun downmix(total: Int, channels: Int, sample: (Int) -> Float): FloatArray {
        if (channels <= 1) return FloatArray(total) { sample(it) }
        val frames = total / channels
        return FloatArray(frames) { frame ->
            var sum = 0f
            for (c in 0 until channels) sum += sample(frame * channels + c)
            sum / channels
        }
    }

    /**
     * Linear-interpolation resampler.
     *
     * Adequate to get the pipeline working, but it is a known weak point: the
     * imaging artefacts it leaves sit in the same high band the countermeasure
     * inspects, so a file resampled here is not directly comparable to one
     * captured at 16 kHz natively. Swap in a windowed-sinc/polyphase resampler
     * before trusting any offline evaluation numbers.
     */
    private fun resampleTo16k(input: FloatArray, inputRate: Int): FloatArray {
        if (inputRate == AudioFormatSpec.SAMPLE_RATE) return input
        require(inputRate > 0) { "invalid sample rate $inputRate" }

        val ratio = AudioFormatSpec.SAMPLE_RATE.toDouble() / inputRate
        val outLength = (input.size * ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outLength)
        for (i in 0 until outLength) {
            val src = i / ratio
            val i0 = src.toInt()
            val i1 = minOf(i0 + 1, input.size - 1)
            val frac = (src - i0).toFloat()
            out[i] = input[i0] + (input[i1] - input[i0]) * frac
        }
        return out
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val PCM16_FULL_SCALE = 32768f

        // android.media.AudioFormat constants, restated to keep this file free
        // of an import that collides with MediaFormat usage above.
        const val ENCODING_PCM_16BIT = 2
        const val ENCODING_PCM_FLOAT = 4
    }
}

private fun MediaFormat.optInt(key: String, fallback: Int): Int =
    if (containsKey(key)) getInteger(key) else fallback

/** Append-only float buffer that grows geometrically. */
private class GrowableFloats(initialCapacity: Int = 1 shl 16) {
    private var data = FloatArray(initialCapacity)
    var size = 0
        private set

    fun add(value: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    fun toArray(): FloatArray = data.copyOf(size)
}
