package app.vela.core.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The low-RAM predicate gates every memory adaptation in the app, and it has already shipped wrong
 * twice: an exclusive `1..127` that skipped 128 - the single heap class the target phones use - and
 * a zero-means-roomy fallthrough that sent a device we knew nothing about down the memory-hungry
 * path. Neither was catchable without a device that reproduced it, which nobody on the dev side has.
 * Hence these.
 */
class LowRamModeTest {

    // ---- the off-by-one that started this ----

    @Test
    fun `heap class 128 is low-RAM, the boundary the first version excluded`() {
        assertTrue(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 128, totalRamMb = 3000))
    }

    @Test
    fun `heap class 127 and below are low-RAM`() {
        assertTrue(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 127, totalRamMb = 3000))
        assertTrue(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 96, totalRamMb = 3000))
        assertTrue(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 1, totalRamMb = 3000))
    }

    @Test
    fun `heap class above the ceiling is not low-RAM on its own`() {
        assertFalse(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 129, totalRamMb = 3000))
        assertFalse(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 192, totalRamMb = 3000))
    }

    // ---- unknown must never read as roomy ----

    @Test
    fun `both probes unreadable is treated as constrained, not roomy`() {
        // 0 means "could not read it". Failing to the low-RAM path costs a roomy phone about a
        // second on its first mic tap; failing the other way can OOM a phone with no headroom.
        assertTrue(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 0, totalRamMb = 0))
    }

    @Test
    fun `one unreadable probe does not veto a good reading from the other`() {
        // Heap class unknown but 3 GB of RAM: roomy.
        assertFalse(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 0, totalRamMb = 3000))
        // RAM unknown but a 256 MB heap class: roomy.
        assertFalse(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 256, totalRamMb = 0))
        // RAM unknown and a small heap class: constrained.
        assertTrue(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 96, totalRamMb = 0))
    }

    // ---- total RAM, the signal that actually describes the device ----

    @Test
    fun `a nominal 2 GB phone is low-RAM even with a generous heap class`() {
        // totalMem reports what the OS can hand out, so a 2 GB phone lands near 1900 MB.
        assertTrue(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 192, totalRamMb = 1900))
    }

    @Test
    fun `total RAM boundary is inclusive`() {
        assertTrue(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 256, totalRamMb = 2048))
        assertFalse(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 256, totalRamMb = 2049))
    }

    // ---- the canonical flag always wins ----

    @Test
    fun `isLowRamDevice alone is enough however roomy the other probes look`() {
        assertTrue(LowRamMode.classify(isLowRamDevice = true, heapClassMb = 512, totalRamMb = 8000))
    }

    // ---- the device every measurement in AGENTS.md was taken on ----

    @Test
    fun `the M5 dev phone stays on the normal path`() {
        // Measured on device: heapClassMb=256, totalRamMb=2878 (MemTotal 2947424 kB). If this ever
        // flips, every memory figure recorded in AGENTS.md was taken on a different code path than
        // the one that ships to a roomy phone.
        assertFalse(LowRamMode.classify(isLowRamDevice = false, heapClassMb = 256, totalRamMb = 2878))
    }
}
