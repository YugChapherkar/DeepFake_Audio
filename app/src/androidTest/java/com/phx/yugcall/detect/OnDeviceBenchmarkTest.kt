package com.phx.yugcall.detect

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.phx.yugcall.audio.AudioFormatSpec
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin
import kotlin.random.Random

private const val TAG = "VoxShieldBenchmark"

/**
 * Measures real inference latency on the device this runs on.
 *
 * Run it on the target handset — the whole point is that a desktop or emulator
 * number says nothing about a phone:
 *
 *     ./gradlew :app:connectedDebugAndroidTest
 *     adb logcat -s VoxShieldBenchmark
 *
 * The budget is [AudioFormatSpec.HOP_SAMPLES] / 16 kHz = 1000 ms: a window must
 * be scored before the next one arrives, or live analysis falls behind and the
 * capture flow starts dropping windows.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceBenchmarkTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Speech-shaped enough to exercise the model realistically; content does not affect timing. */
    private fun syntheticWindow(): FloatArray {
        val rng = Random(7)
        return FloatArray(AudioFormatSpec.WINDOW_SAMPLES) { i ->
            val t = i.toDouble() / AudioFormatSpec.SAMPLE_RATE
            (0.3 * sin(2 * Math.PI * 180 * t) + 0.1 * rng.nextDouble(-1.0, 1.0)).toFloat()
        }
    }

    private fun measure(backend: ExecutionBackend, threads: Int): Result? {
        val detector = OnnxSpoofDetector.fromAsset(
            context = context,
            backend = backend,
            threads = threads,
        )
        val ready = detector.state as? DetectorState.Ready
        if (ready == null) {
            Log.w(TAG, "skipping $backend x$threads: ${(detector.state as DetectorState.Unavailable).reason}")
            detector.close()
            return null
        }

        detector.use { session ->
            val window = syntheticWindow()
            repeat(WARMUP) { session.score(window) }

            val samples = DoubleArray(ITERATIONS)
            for (i in 0 until ITERATIONS) {
                val started = System.nanoTime()
                session.score(window)
                samples[i] = (System.nanoTime() - started) / 1_000_000.0
            }
            samples.sort()
            return Result(
                requested = backend,
                applied = ready.backend,
                threads = threads,
                median = samples[samples.size / 2],
                p95 = samples[(samples.size * 95 / 100).coerceAtMost(samples.size - 1)],
            )
        }
    }

    @Test
    fun benchmarkEveryBackend() {
        Log.i(TAG, "device=${Build.MANUFACTURER} ${Build.MODEL} soc=${socName()} sdk=${Build.VERSION.SDK_INT}")
        Log.i(TAG, "budget=${BUDGET_MS.toInt()} ms per window (1 s hop)")
        Log.i(TAG, String.format("%-10s %-10s %7s %9s %9s", "REQUESTED", "APPLIED", "THREADS", "MEDIAN", "P95"))

        val results = buildList {
            for (backend in ExecutionBackend.entries) {
                for (threads in THREAD_COUNTS) {
                    measure(backend, threads)?.let { add(it) }
                }
            }
        }

        for (r in results) {
            Log.i(
                TAG,
                String.format(
                    "%-10s %-10s %7d %8.1fms %8.1fms%s",
                    r.requested.label, r.applied.label, r.threads, r.median, r.p95,
                    if (r.median > BUDGET_MS) "  OVER BUDGET" else "",
                ),
            )
        }

        assertTrue("no backend produced a measurement", results.isNotEmpty())

        val best = results.minBy { it.median }
        Log.i(TAG, "fastest: ${best.applied.label} x${best.threads} at ${"%.1f".format(best.median)} ms")

        // Not an assertion on the exact number — hardware varies — but live
        // analysis is simply broken if nothing can keep up with the hop.
        assertTrue(
            "no configuration scored a window within the ${BUDGET_MS.toInt()} ms hop " +
                "(fastest was ${"%.1f".format(best.median)} ms on ${best.applied.label})",
            best.median < BUDGET_MS,
        )
    }

    private fun socName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
        } else {
            Build.HARDWARE
        }

    private data class Result(
        val requested: ExecutionBackend,
        val applied: ExecutionBackend,
        val threads: Int,
        val median: Double,
        val p95: Double,
    )

    private companion object {
        const val WARMUP = 3
        const val ITERATIONS = 20
        val THREAD_COUNTS = listOf(1, 2, 4)
        val BUDGET_MS =
            AudioFormatSpec.HOP_SAMPLES * 1000.0 / AudioFormatSpec.SAMPLE_RATE
    }
}
