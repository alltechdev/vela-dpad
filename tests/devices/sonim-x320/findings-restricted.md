# Sonim X320 - restricted flavor findings (480x854 @ 320dpi)

`VELA_PKG=app.vela.restricted.debug bash tests/devices/full_coverage.sh sonim-x320` ->
**20 COVERED, 0 MISSED / FULLY COVERED.** Frames in `screenshots/full-restricted/`.

Verified visually: Settings has no Place pages section (the five locked toggles are gone);
place sheet (08/09) shows no photos, no reviews section and no Website pill - Directions + Call
only; the mic IS present in the search bar and voice capture opens the system recognizer (17),
so voice search stays available on restricted by design; parking works end to end (18-20, Find
my car focused with a D-pad ring). Both restricted geometries are now FULLY COVERED 20/20.
