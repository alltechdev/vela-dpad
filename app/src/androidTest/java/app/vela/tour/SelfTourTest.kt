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

        // ---- Settings hub: open, clip-check the top rows, flavor assertions ------------------
        // Settings is hub-and-spoke: the gear opens a short category list (the hub); each row
        // opens its own sub-screen (spoke) on the shared SettingsScaffold.
        byDesc("Settings")!!.click()
        val appearance = byText("Appearance")
        assertNotNull("Settings hub did not open", appearance)
        assertOnScreen("Appearance", appearance!!)
        byText("Map")?.let { assertOnScreen("Map", it) }
        shot("05-settings-hub")

        // D-pad walk: DOWN must land focus in the content (the DOWN-from-Back bridge), and the
        // focused row is READ from the tree - the old harness could only infer this from pixels.
        dpad(KeyEvent.KEYCODE_DPAD_DOWN)
        dpad(KeyEvent.KEYCODE_DPAD_DOWN)
        val focused = device.findObject(androidx.test.uiautomator.By.focused(true))
        assertNotNull("D-pad walk lost focus in the Settings hub", focused)
        shot("06-settings-dpad-walk")

        // FLAVOR ASSERTION - a restriction leaking back into restricted Settings MUST fail here.
        // The Place pages CATEGORY row is the whole flavor surface now: restricted hides it from
        // the hub, so its toggles (Show reviews / Hide adult categories / ...) are unreachable
        // (and hard-locked in their holders regardless).
        if (SelfTour.restricted) {
            assertTrue("Place pages category must be ABSENT on restricted", gone("Place pages"))
        }

        // ---- One spoke end-to-end: Appearance (opens focused, rows on-screen, BACK -> hub) ----
        byText("Appearance")!!.click()
        val followSystem = byText("Follow system")
        assertNotNull("Appearance spoke did not open", followSystem)
        assertOnScreen("Follow system", followSystem!!)
        shot("07-settings-spoke-appearance")
        dpad(KeyEvent.KEYCODE_DPAD_DOWN)
        dpad(KeyEvent.KEYCODE_DPAD_DOWN)
        assertNotNull(
            "D-pad walk lost focus in the Appearance spoke",
            device.findObject(androidx.test.uiautomator.By.focused(true)),
        )
        device.pressBack()   // spoke -> hub
        Thread.sleep(800)
        assertNotNull("BACK from a spoke did not return to the hub", byText("Appearance"))

        device.pressBack()   // hub -> map
        Thread.sleep(800)

        // ---- Search overlay: opens on OK, BACK leaves it (the no-focus-trap rule) -------------
        dpad(KeyEvent.KEYCODE_DPAD_DOWN)
        dpad(KeyEvent.KEYCODE_DPAD_CENTER, settleMs = 900)
        shot("08-search-overlay")
        // BACK unwinds the overlay's layered state (armed field -> entry page -> map); allow up
        // to 3 presses, bounded. Each press is followed by a WAITING check (2.5s), never a 0-wait
        // findObject: a blind instant re-check raced the close animation, fired an extra BACK on
        // the bare map, and exited the app (the 46s X320 flake - this suite's own bug, not Vela's).
        var backTries = 0
        while (
            device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.desc("Settings")), 2_500L) == null &&
            backTries < 3
        ) {
            device.pressBack(); backTries++
        }
        shot("09-back-on-map")
        assertNotNull("BACK did not return to the map within 3 presses", byDesc("Settings"))

        // ---- Search -> results -> place sheet (live network; mock GPS set by the wrapper) ------
    }

    /**
     * WIP - content + parking surfaces. NOT yet proven deterministic (the search-results D-pad
     * walk and the cold-start fix timing both misbehave differently per geometry), so it is
     * EXCLUDED from the proven tour: a pass must never mean less than it claims. The external
     * full_coverage gate remains the MANDATORY record for these surfaces (AGENTS migration rule).
     * Skips inside are HARD FAILURES by design - keep that when finishing this.
     */
    @org.junit.Ignore("WIP: content surfaces not yet deterministic - external gate remains the record")
    @Test
    fun contentAndParkingTour_WIP() {
        ActivityScenario.launch(MainActivity::class.java)
        device.waitForIdle()
        // The search is CENTER-relative: on a cold start the first GPS fix lands seconds after
        // launch and the map is still on the world view - searching then finds nothing (the
        // external harness never hit this only because it moved slower). Do what a user does:
        // recenter on the fix first, then search.
        device.wait(
            androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.descContains("my location")),
            8_000L,
        )?.click()
        Thread.sleep(3500)
        val chip = byText("Coffee")
        assertNotNull("Coffee chip missing from the bare map", chip)
        chip!!.let { chip ->
            chip.click()
            val results = device.wait(
                androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.textContains("results")),
                20_000L,
            )
            if (results != null) {
                shot("10-search-results")
                // Open the first result. Live content shifts what a FIXED D-pad walk lands on
                // (the 48s flake), so walk-then-VERIFY with one bounded retry: after CENTER, a
                // place sheet must show its Directions pill; if not, one more DOWN and retry.
                // The assertion is unweakened - the sheet must open or the tour fails.
                dpad(KeyEvent.KEYCODE_DPAD_DOWN); dpad(KeyEvent.KEYCODE_DPAD_DOWN); dpad(KeyEvent.KEYCODE_DPAD_DOWN)
                dpad(KeyEvent.KEYCODE_DPAD_CENTER, settleMs = 2500)
                if (device.findObject(androidx.test.uiautomator.By.text("Directions")) == null) {
                    device.pressBack(); Thread.sleep(700)
                    dpad(KeyEvent.KEYCODE_DPAD_DOWN)
                    dpad(KeyEvent.KEYCODE_DPAD_CENTER, settleMs = 2500)
                }
                shot("11-place-sheet")
                // The Directions pill must exist in BOTH flavors; Website must be ABSENT on restricted.
                assertNotNull("Directions pill missing on place sheet", byText("Directions"))
                if (SelfTour.restricted) {
                    assertTrue("Website pill must be ABSENT on restricted", gone("Website"))
                    assertTrue("reviews must be ABSENT on restricted", gone("Reviews"))
                }
                device.pressBack(); Thread.sleep(600)
                device.pressBack(); Thread.sleep(600)
            } else {
                org.junit.Assert.fail("search returned no results within 20s - content surfaces MUST run (network + mock GPS are run preconditions); a silent skip is not a pass")
            }
        }

        // ---- Parking: save -> hub -> car sheet -> clear (flavor-independent) -------------------
        device.pressBack(); Thread.sleep(500) // ensure bare map
        val pSave = byDesc("Save parking spot")
        if (pSave != null) {
            pSave.click(); Thread.sleep(1200)
            shot("12-parking-saved")
            byDesc("Parked car")?.click(); Thread.sleep(1000)
            assertNotNull("parking hub menu did not open", byText("Find my car"))
            shot("13-parking-menu")
            byText("Find my car")!!.click(); Thread.sleep(1500)
            assertNotNull("Parked car sheet did not open", byText("Parked car"))
            shot("14-parking-car-sheet")
            device.pressBack(); Thread.sleep(600)
            // clear the spot so the device is left clean
            byDesc("Parked car")?.click(); Thread.sleep(1000)
            byText("Clear parking")?.click(); Thread.sleep(600)
        } else {
            org.junit.Assert.fail("parking P button not found - the parking surfaces MUST run")
        }
    }
}
