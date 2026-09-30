package com.phx.yugcall.detect

/**
 * Which ONNX Runtime execution provider to try.
 *
 * On the current target hardware (Pixel 6a, Google Tensor G1) there is no path
 * to the Edge TPU:
 *
 *  - The Google Tensor SDK / LiteRT TPU workflow covers Tensor G5/G6 (the
 *    Pixel 10 family). Tensor G1 is not supported.
 *  - ONNX Runtime ships no Edge TPU execution provider. The Android AAR exposes
 *    only NNAPI, XNNPACK and QNN (Qualcomm).
 *  - NNAPI, the one theoretical route to Tensor's TPU, was deprecated in
 *    Android 15, needs a fully int8 model, and supports an operator set that
 *    AASIST's heterogeneous graph attention does not fit. Expect the graph to
 *    be partitioned across NNAPI and CPU, which usually costs more than it
 *    saves.
 *
 * [NNAPI] is kept here so that claim can be measured on the device rather than
 * taken on trust — see `OnDeviceBenchmarkTest`.
 */
enum class ExecutionBackend(val label: String) {
    /**
     * XNNPACK kernels. Expected to win on paper, but measured at 877 ms median
     * (2 threads) on a Pixel 6a versus 302 ms for plain [CPU] — see
     * docs/MODEL.md. Kept for measurement on other hardware.
     */
    XNNPACK("XNNPACK"),

    /** Deprecated in Android 15. Present for measurement, not because it should win. */
    NNAPI("NNAPI"),

    /**
     * ONNX Runtime's built-in CPU kernels with no accelerator. Default: fastest
     * configuration measured on the target device (302 ms median at 2 threads).
     */
    CPU("CPU"),
    ;

    companion object {
        val DEFAULT = CPU
    }
}
