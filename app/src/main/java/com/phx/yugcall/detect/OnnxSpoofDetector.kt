package com.phx.yugcall.detect

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.FileNotFoundException
import java.nio.FloatBuffer

private const val TAG = "OnnxSpoofDetector"

/**
 * Runs an AASIST-style countermeasure through ONNX Runtime.
 *
 * Deliberately CPU-only for now. At 85k parameters the arithmetic is trivial,
 * and the NPU story on Android is not worth the complexity yet: NNAPI was
 * deprecated in Android 15, and the LiteRT NPU delegate currently ships for
 * Qualcomm while Pixel and Samsung are still pending. AASIST's graph-attention
 * blocks are also the ops most likely to fall back to CPU inside a vendor
 * compiler. Measure on CPU first; move only if the numbers demand it.
 */
class OnnxSpoofDetector private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val inputName: String,
    private val outputName: String,
    private val inputSamples: Int,
    modelName: String,
) : SpoofDetector {

    override val state: DetectorState = DetectorState.Ready(modelName, inputSamples)

    @Synchronized
    override fun score(samples: FloatArray): WindowScore {
        val fitted = fitToLength(samples, inputSamples)
        val shape = longArrayOf(1, inputSamples.toLong())

        OnnxTensor.createTensor(environment, FloatBuffer.wrap(fitted), shape).use { input ->
            session.run(mapOf(inputName to input), setOf(outputName)).use { results ->
                @Suppress("UNCHECKED_CAST")
                val logits = (results[0].value as Array<FloatArray>)[0]
                check(logits.size == 2) { "expected 2 logits, got ${logits.size}" }
                // Index order is fixed by the AASIST label convention: 0 spoof, 1 bona fide.
                return WindowScore(spoofLogit = logits[0], bonafideLogit = logits[1])
            }
        }
    }

    override fun close() {
        runCatching { session.close() }.onFailure { Log.w(TAG, "session close failed", it) }
        // OrtEnvironment is a process-wide singleton; closing it here would
        // break any other session, so it is intentionally left open.
    }

    companion object {
        /** Bundled model asset. Absent from the repo build until exported — see tools/export_aasist_onnx.py. */
        const val DEFAULT_ASSET = "aasist_l.onnx"

        /**
         * Loads [assetName] from `src/main/assets`.
         *
         * Returns an [UnavailableDetector] rather than throwing when the model
         * is missing or unreadable, so the app can start and tell the user it
         * cannot analyse audio instead of crashing or inventing a score.
         */
        fun fromAsset(context: Context, assetName: String = DEFAULT_ASSET): SpoofDetector {
            return try {
                val bytes = context.assets.open(assetName).use { it.readBytes() }
                val environment = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    // Two threads is the sweet spot for a model this size: more
                    // threads spend longer in the barrier than in the kernels.
                    setIntraOpNumThreads(2)
                    runCatching { addXnnpack(mapOf("intra_op_num_threads" to "2")) }
                        .onFailure { Log.i(TAG, "XNNPACK unavailable, using default CPU kernels") }
                }
                val session = environment.createSession(bytes, options)

                val inputName = session.inputNames.firstOrNull()
                    ?: error("model exposes no inputs")
                val outputName = session.outputNames.firstOrNull()
                    ?: error("model exposes no outputs")
                val inputSamples = session.inputInfo[inputName]
                    ?.let { (it.info as? ai.onnxruntime.TensorInfo)?.shape?.lastOrNull()?.toInt() }
                    ?.takeIf { it > 0 }
                    ?: error("could not read the model's input length")

                Log.i(TAG, "loaded $assetName: input=$inputName[$inputSamples] output=$outputName")
                OnnxSpoofDetector(
                    environment = environment,
                    session = session,
                    inputName = inputName,
                    outputName = outputName,
                    inputSamples = inputSamples,
                    modelName = assetName,
                )
            } catch (e: FileNotFoundException) {
                Log.w(TAG, "model asset '$assetName' not bundled", e)
                UnavailableDetector("Model '$assetName' is not bundled in this build")
            } catch (t: Throwable) {
                Log.e(TAG, "failed to initialise ONNX Runtime", t)
                UnavailableDetector("Could not load the detector: ${t.message ?: t::class.java.simpleName}")
            }
        }
    }
}
