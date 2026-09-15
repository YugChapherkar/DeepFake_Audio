package com.phx.yugcall.detect

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import java.io.FileNotFoundException
import java.nio.FloatBuffer

private const val TAG = "OnnxSpoofDetector"

/**
 * Runs an AASIST-style countermeasure through ONNX Runtime.
 *
 * CPU-first by design. See [ExecutionBackend] for why the NPU is not a live
 * option on Tensor G1 hardware, and `docs/MODEL.md` for measured latency.
 */
class OnnxSpoofDetector private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val inputName: String,
    private val outputName: String,
    private val inputSamples: Int,
    modelName: String,
    backend: ExecutionBackend,
    threads: Int,
) : SpoofDetector {

    override val state: DetectorState =
        DetectorState.Ready(modelName, inputSamples, backend, threads)

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
        const val DEFAULT_ASSET = "aasist_l.onnx"

        /**
         * Two intra-op threads matches Tensor G1's pair of Cortex-X1 cores.
         * More threads spend longer in the barrier than in the kernels for a
         * model this small.
         */
        const val DEFAULT_THREADS = 2

        /**
         * Loads [assetName] from `src/main/assets`.
         *
         * Returns an [UnavailableDetector] rather than throwing when the model
         * is missing or unreadable, so the app can start and tell the user it
         * cannot analyse audio instead of crashing or inventing a score.
         *
         * If [backend] cannot be enabled the session still loads on plain CPU,
         * and [DetectorState.Ready.backend] reports what actually applied.
         */
        fun fromAsset(
            context: Context,
            assetName: String = DEFAULT_ASSET,
            backend: ExecutionBackend = ExecutionBackend.DEFAULT,
            threads: Int = DEFAULT_THREADS,
        ): SpoofDetector {
            return try {
                val bytes = context.assets.open(assetName).use { it.readBytes() }
                val environment = OrtEnvironment.getEnvironment()

                var applied = ExecutionBackend.CPU
                val options = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setIntraOpNumThreads(threads)
                    when (backend) {
                        ExecutionBackend.XNNPACK -> runCatching {
                            addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                            applied = ExecutionBackend.XNNPACK
                        }.onFailure { Log.i(TAG, "XNNPACK unavailable, falling back to CPU", it) }

                        ExecutionBackend.NNAPI -> runCatching {
                            addNnapi()
                            applied = ExecutionBackend.NNAPI
                        }.onFailure { Log.i(TAG, "NNAPI unavailable, falling back to CPU", it) }

                        ExecutionBackend.CPU -> Unit
                    }
                }
                val session = environment.createSession(bytes, options)

                val inputName = session.inputNames.firstOrNull() ?: error("model exposes no inputs")
                val outputName = session.outputNames.firstOrNull() ?: error("model exposes no outputs")
                val inputSamples = session.inputInfo[inputName]
                    ?.let { (it.info as? TensorInfo)?.shape?.lastOrNull()?.toInt() }
                    ?.takeIf { it > 0 }
                    ?: error("could not read the model's input length")

                Log.i(TAG, "loaded $assetName on ${applied.label} x$threads: in=$inputName[$inputSamples]")
                OnnxSpoofDetector(
                    environment = environment,
                    session = session,
                    inputName = inputName,
                    outputName = outputName,
                    inputSamples = inputSamples,
                    modelName = assetName,
                    backend = applied,
                    threads = threads,
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
