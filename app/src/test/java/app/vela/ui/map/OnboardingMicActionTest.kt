package app.vela.ui.map

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The onboarding download-plan decision - the safe, deterministic home for a bug the on-device path
 * can only reach by filling a real phone's disk (review of PR #87).
 *
 * When a user tickets BOTH speech models in setup they download one at a time (shared temp paths).
 * If the VOICE is requested but refuses to start - low disk, or a bad voice id - `downloadVoice`
 * returns before ever setting `voiceDownloadingId`, which used to satisfy the queue's
 * `voiceDownloadingId == null` wait INSTANTLY and start the 58 MB mic download on the same full
 * disk. [onboardingMicAction] is the fix, extracted pure so this can prove it without a device.
 */
class OnboardingMicActionTest {

    @Test fun micNotRequested_doesNothing_evenWhenVoiceStarted() {
        assertEquals(OnboardingMicAction.NONE, onboardingMicAction(voiceRequested = true, voiceStarted = true, micRequested = false))
        assertEquals(OnboardingMicAction.NONE, onboardingMicAction(voiceRequested = false, voiceStarted = false, micRequested = false))
    }

    /** THE regression. Voice asked for, voice refused (disk full / bad id) -> the mic must be
     *  abandoned, NOT started. Before the fix this returned QUEUE and a second large download began
     *  on a device that had just reported it was out of room. */
    @Test fun voiceRequestedButRefused_abandonsMic() {
        assertEquals(OnboardingMicAction.ABANDON, onboardingMicAction(voiceRequested = true, voiceStarted = false, micRequested = true))
    }

    @Test fun voiceRequestedAndStarted_queuesMicBehindIt() {
        assertEquals(OnboardingMicAction.QUEUE, onboardingMicAction(voiceRequested = true, voiceStarted = true, micRequested = true))
    }

    /** Mic only, no voice: nothing to wait on, so it queues (and starts immediately). A voice that
     *  "did not start" is irrelevant when it was never requested - must not be read as a refusal. */
    @Test fun micOnly_queuesRegardlessOfVoiceStartedFlag() {
        assertEquals(OnboardingMicAction.QUEUE, onboardingMicAction(voiceRequested = false, voiceStarted = false, micRequested = true))
        assertEquals(OnboardingMicAction.QUEUE, onboardingMicAction(voiceRequested = false, voiceStarted = true, micRequested = true))
    }
}
