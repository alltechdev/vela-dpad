package app.vela.tour

import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.vela.MainActivity
import app.vela.tour.SelfTour.assertOnScreen
import app.vela.tour.SelfTour.byDesc
import app.vela.tour.SelfTour.byText
import app.vela.tour.SelfTour.device
import app.vela.tour.SelfTour.dpad
import app.vela.tour.SelfTour.gone
import app.vela.tour.SelfTour.shot
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The in-process self-coverage tour: drives the REAL app (R8-minified debug build) with
 * real-dispatcher D-pad input, asserts accessibility-tree ground truth (existence, focus,
 * on-screen bounds - the same source the external uiautomator harness read, queried ~100x
 * faster in-process), and captures a real-framebuffer still per surface. Run via
 * `tests/devices/self_coverage.sh <device-id>`, which sets device geometry, records scrcpy
 * video throughout, pulls the stills for the mandatory eyeball pass, and prints the checklist.
 *
 * FLAVOR-AWARE: the same test asserts each restriction ABSENT on `restricted` and the shared
 * chrome present on both - direct assertions instead of two blind runs.
 */
@RunWith(AndroidJUnit4::class)
class SelfTourTest {

    @Test
    fun tour() {
        ActivityScenario.launch(MainActivity::class.java)
        device.waitForIdle()

        // ---- First-run surfaces when present (fresh install), else no-op --------------------
        if (byText("Get started") != null) {
            shot("01-welcome")
            byText("Get started")!!.click()
            repeat(4) {
                val notNow = device.wait(
                    androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text("Not now")),
                    3_000L,
                )
                if (notNow != null) {
                    shot("02-onboarding-dialog-$it")
                    notNow.click()
                    Thread.sleep(700)
                }
            }
        }

        // ---- Bare map + the D-pad entry point ------------------------------------------------
        Thread.sleep(1200)
        shot("03-bare-map")
        dpad(KeyEvent.KEYCODE_DPAD_DOWN)   // ambient -> search bar (the documented first stop)
        shot("04-map-first-focus")

        // The mic: present in BOTH flavors (voice search is not a restriction).
        assertNotNull("voice-search mic missing from the search bar", byDesc("Voice search"))

        // ---- Settings: open, clip-check the top rows, flavor assertions ----------------------
        byDesc("Settings")!!.click()
        val appearance = byText("Appearance")
        assertNotNull("Settings did not open", appearance)
        assertOnScreen("Appearance", appearance!!)
        byText("Follow system")?.let { assertOnScreen("Follow system", it) }
        shot("05-settings-top")

        // D-pad walk: DOWN must land focus in the content (the DOWN-from-Back bridge), and the
        // focused row is READ from the tree - the old harness could only infer this from pixels.
        dpad(KeyEvent.KEYCODE_DPAD_DOWN)
        dpad(KeyEvent.KEYCODE_DPAD_DOWN)
        val focused = device.findObject(androidx.test.uiautomator.By.focused(true))
        assertNotNull("D-pad walk lost focus in Settings", focused)
        shot("06-settings-dpad-walk")

        // FLAVOR ASSERTIONS - a restriction leaking back into restricted Settings MUST fail here.
        if (SelfTour.restricted) {
            assertTrue("Place pages section must be ABSENT on restricted", gone("Place pages"))
            assertTrue("Show reviews row must be ABSENT on restricted", gone("Show reviews"))
            assertTrue("Hide adult categories row must be ABSENT on restricted", gone("Hide adult categories"))
        }

        device.pressBack()
        Thread.sleep(800)

        // ---- Search overlay: opens on OK, BACK leaves it (the no-focus-trap rule) -------------
        dpad(KeyEvent.KEYCODE_DPAD_DOWN)
        dpad(KeyEvent.KEYCODE_DPAD_CENTER, settleMs = 900)
        shot("07-search-overlay")
        device.pressBack()
        Thread.sleep(800)
        shot("08-back-on-map")
        assertNotNull("BACK did not return to the map (search bar gone)", byDesc("Settings"))
    }
}
