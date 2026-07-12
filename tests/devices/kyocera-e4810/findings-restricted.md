# Kyocera e4810 - restricted flavor findings (240x320 @ 160dpi)

`VELA_PKG=app.vela.restricted.debug bash tests/devices/full_coverage.sh kyocera-e4810` ->
**20 COVERED, 0 MISSED / FULLY COVERED.** Frames in `screenshots/full-restricted/`.

Verified visually: no Place pages section in Settings (the five locked toggles are gone), place
sheet shows no photos / reviews / Website pill, and the mic IS present in the search bar (voice
search stays available on restricted, by design). Parking works (save + hub + Parked-car sheet).
The voice-capture frame shows the system recognizer opening (the mic works) - the earlier MISS
was a harness poll that only matched pre-listening strings and missed the silence-result state.
