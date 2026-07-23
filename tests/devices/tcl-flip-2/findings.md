# TCL Flip 2 - findings

- **Screen:** 2.8", 240x320 PORTRAIT, ~143 dpi. A flip phone; the main screen is portrait.
- **Emulate:** `adb shell wm size 240x320; adb shell wm density 160`
- **Auditor:** default (`bash tests/small_screen/audit_smallscreen.sh`).

## Status: FULLY COVERED (20/20) at SIMULATED 240x320 @ 160dpi - own full run, not real hardware

`bash tests/devices/full_coverage.sh tcl-flip-2` (mock GPS fix over Philadelphia) reports
**20 COVERED, 0 MISSED / RESULT: FULLY COVERED**. This profile now runs as its OWN row in the gate
with its own frames in [`screenshots/full/`](screenshots/full/) (01 welcome .. 20 parked-car sheet),
each checked by eye - it no longer piggybacks on the Kyocera e4810 record, even though the emulated
geometry is identical. Layout analysis and fix history for this geometry live in
[kyocera-e4810/findings.md](../kyocera-e4810/findings.md). If a real unit ever behaves differently
(panel quirk), capture it here.

## CPU: 32-bit ARM (armeabi-v7a) - CONFIRMED on real hardware, 2026-07-21

**This device is arm32, not arm64.** It is the first confirmed 32-bit unit in the matrix, and it
broke a feature in a way no simulated profile could ever catch.

`app/build.gradle.kts` used to strip the armeabi-v7a copies of `libonnxruntime.so` and
`libsherpa-onnx*.so` - a block inherited verbatim from upstream, whose comment read *"Vela targets
arm64 phones ... for no device we support"*. True upstream, false here. On this phone the APK
installed, the map drew (MapLibre stayed multi-ABI), and **every sherpa-onnx feature was dead**:
on-device voice search AND the Vela neural voice, both `UnsatisfiedLinkError` at
`OfflineRecognizer` construction. `WhisperRecognizer` swallowed it into `Reason.MODEL`, so the
dialog said *"the voice model isn't ready, re-download it"* - advice that could not work. The
tester re-downloaded 47 MB twice (issue #81). Fixed by shipping v7a and dropping x86/x86_64
instead (+1.8 MB net).

**Why the gate missed it, and the standing lesson:** the profile emulates *geometry only* -
`wm size 240x320; wm density 160` on whatever device is plugged in, which is arm64. So the
coverage run says FULLY COVERED while a whole native subsystem is absent on the real unit. Screen
simulation cannot test ABI, RAM ceilings, mic hardware, or GPU drivers. **Any feature backed by a
native `.so` needs a real 32-bit unit, or at minimum a check that its ABI is in the APK.**

Still open for this device: it is also low-RAM (see #83), so whether Whisper tiny - ~103 MB of
weights held for the process lifetime - actually *loads* here now that the `.so` exists is a
separate question from whether it can link.
