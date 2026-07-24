package app.vela.core.data

/**
 * Whether this device is memory-constrained, exposed as a `:core`-visible flag.
 *
 * Same shape and reason as [CategoryFilter.enabled]: the detection lives in `:app`
 * (`app.vela.ui.MemoryPressure`, which needs `ActivityManager`), but the behaviour it gates has to
 * act down at the data-source seam. `:core` stays UI-agnostic and never reads an app holder, so the
 * app pushes the value in at startup instead.
 *
 * Off by default, which keeps every roomier device byte-identical to previous behaviour.
 */
object LowRamMode {

    /** Set once from `VelaApp.onCreate` after `MemoryPressure.init`. */
    @Volatile var enabled: Boolean = false

    /**
     * Heap-class ceiling for the low-RAM path, INCLUSIVE.
     *
     * 128 is the value that matters, and the one the first version of this got wrong by writing
     * `in 1..127`: it is the heap class OEMs hand out across 1 GB phones and the low end of 2 GB
     * ones, exactly the class of device issue #83 was filed from. Excluding it meant the phone the
     * work was written for could plausibly have received none of it. 192 and up stays normal.
     */
    const val LOW_HEAP_CLASS_MB = 128

    /**
     * Total-RAM ceiling for the low-RAM path, INCLUSIVE.
     *
     * `ActivityManager.MemoryInfo.totalMem` reports what the OS can hand out, meaningfully less than
     * the marketing figure once the kernel has taken its share: a nominal 2 GB phone reports roughly
     * 1900 MB and lands inside this, a 3 GB phone roughly 2800 MB and does not. The M5 dev phone
     * reports 2878 MB, so it stays on the normal path and its recorded measurements stay comparable.
     */
    const val LOW_TOTAL_RAM_MB = 2048

    /**
     * Decide whether a device is memory-constrained, from probes the `:app` side gathers.
     *
     * Pure and testable ON PURPOSE. This predicate has already been wrong twice - an exclusive
     * `1..127` that skipped the single most important heap class, and a zero-means-roomy fallthrough
     * that sent an unknown device down the memory-hungry path - and neither was catchable without a
     * device that reproduced it. It is `:core` so it can have unit tests.
     *
     * Pass 0 for a probe that could not be read; 0 never counts as evidence of a roomy device.
     *
     * @param isLowRamDevice `ActivityManager.isLowRamDevice`, the canonical flag, which only
     *   Go-configured builds set.
     * @param heapClassMb `ActivityManager.memoryClass`, a Dalvik knob an OEM can set to anything.
     * @param totalRamMb total system RAM, the signal that actually describes the device.
     */
    fun classify(isLowRamDevice: Boolean, heapClassMb: Int, totalRamMb: Int): Boolean = when {
        isLowRamDevice -> true
        heapClassMb in 1..LOW_HEAP_CLASS_MB -> true
        totalRamMb in 1..LOW_TOTAL_RAM_MB -> true
        // Neither probe told us anything. Assume constrained: failing this way costs a roomy phone
        // about a second on its first mic tap and first place open, while failing the other way can
        // OOM a phone that had no headroom to begin with.
        heapClassMb == 0 && totalRamMb == 0 -> true
        else -> false
    }
}
