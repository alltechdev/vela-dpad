#!/usr/bin/env python3
"""Fail if a translatable string is missing from a locale, a placeholder drifts, or a translation
went STALE because its English source was edited without it.

Three checks, all against res/values/strings.xml as the source of truth:

1. PRESENCE. Android lint's MissingTranslation doesn't run on assemble/test, so gaps ship silently
   (a missed backfill just shows English for that key). Every translatable <string>/<plurals> key
   must exist in each res/values-<lang>/strings.xml.

2. PLACEHOLDERS. The %1$s / %2$d set must match the default, so a %d fed a String (a runtime crash)
   is caught.

3. STALENESS - added 2026-07-21, after a bug this file was green for. Five strings were renamed in
   English so the speech-to-text model stopped sharing the name "Vela Voice" with the text-to-speech
   voice. All 14 locales kept the OLD wording, so the collision the rename existed to remove was
   still there for every non-English user - and this script passed, because the keys were all
   present and the placeholders all matched. Editing English text simply had no gate.
   `translations.lock.json` records a hash of each English value AND of each locale's value. When an
   English value changes but a locale's value does not, that locale is reported stale.

   The lock is a record of "these translations were reviewed against that English text". After
   updating the locales, run --update to re-record and commit the lock with the change.

   A locale whose translation legitimately does not change when English does (a brand name, an
   identical loanword) will be flagged once; confirm it reads correctly and --update. That is the
   intended cost - the check's job is to make an English edit impossible to land SILENTLY, not to
   guess which locales needed to move.

Run: python3 tools/check-translations.py            (exit 1 on any gap)
     python3 tools/check-translations.py --update   (re-record the lock after translating)
     python3 tools/check-translations.py --selftest (frozen negative control; needs no repo state)

Wired into CI before the build.
"""
import glob
import hashlib
import json
import os
import re
import sys

RES = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")
LOCK = os.path.join(os.path.dirname(__file__), "translations.lock.json")


def digest(body):
    """Stable short hash of a string value. Whitespace-normalised so a re-indent or a wrapped line
    is not mistaken for a copy edit - only the words matter."""
    return hashlib.sha256(" ".join(body.split()).encode("utf-8")).hexdigest()[:16]


def parse(path):
    """Return {name: {"ph": set(placeholders), "sha": digest}} for translatable entries in a file."""
    if not os.path.exists(path):
        return {}
    text = open(path, encoding="utf-8").read()
    out = {}
    for pattern, tag in ((r'<string name="([^"]+)"([^>]*)>(.*?)</string>', "string"),
                         (r'<plurals name="([^"]+)"([^>]*)>(.*?)</plurals>', "plurals")):
        for m in re.finditer(pattern, text, re.S):
            name, attrs, body = m.group(1), m.group(2), m.group(3)
            if 'translatable="false"' in attrs:
                continue
            out[name] = {"ph": set(re.findall(r"%\d+\$[sd]", body)), "sha": digest(body)}
    return out


def read_all():
    """(default strings, {lang: strings}) for the whole res tree."""
    base = parse(os.path.join(RES, "values", "strings.xml"))
    locales = {}
    for f in sorted(glob.glob(os.path.join(RES, "values-*", "strings.xml"))):
        locales[os.path.basename(os.path.dirname(f)).replace("values-", "")] = parse(f)
    return base, locales


def stale_entries(base, locales, lock):
    """The heart of check 3, kept pure so --selftest can exercise it with no files.

    A locale is stale for a key when the English value has changed since the lock was written AND
    that locale's value has NOT. Keys absent from the lock are new and unconstrained - they were
    translated as part of the change that introduced them.
    """
    out = []
    for name, entry in sorted(base.items()):
        rec = lock.get(name)
        if not rec or rec.get("en") == entry["sha"]:
            continue  # new key, or English untouched: nothing to say
        for lang in sorted(locales):
            was = rec.get("loc", {}).get(lang)
            now = locales[lang].get(name, {}).get("sha")
            if was is not None and now is not None and was == now:
                out.append((lang, name))
    return out


def build_lock(base, locales):
    return {
        name: {"en": entry["sha"],
               "loc": {lang: locales[lang][name]["sha"] for lang in sorted(locales) if name in locales[lang]}}
        for name, entry in sorted(base.items())
    }


def selftest():
    """Frozen negative control: prove the staleness rule FAILS on the real bug and PASSES otherwise.

    Runs on synthetic data, so it needs no repo state and cannot rot with the strings. Case 1 is the
    exact shape of the 2026-07-21 bug: English edited, locale untouched.
    """
    lock = {"greeting": {"en": digest("Vela Voice"), "loc": {"de": digest("Vela-Stimme")}}}
    cases = [
        ("english edited, locale untouched -> STALE",
         {"greeting": {"ph": set(), "sha": digest("Vela Voice mic")}},
         {"de": {"greeting": {"ph": set(), "sha": digest("Vela-Stimme")}}}, 1),
        ("english edited, locale also edited -> ok",
         {"greeting": {"ph": set(), "sha": digest("Vela Voice mic")}},
         {"de": {"greeting": {"ph": set(), "sha": digest("Vela Voice Mikro")}}}, 0),
        ("nothing edited -> ok",
         {"greeting": {"ph": set(), "sha": digest("Vela Voice")}},
         {"de": {"greeting": {"ph": set(), "sha": digest("Vela-Stimme")}}}, 0),
        ("brand-new key, not in lock -> ok",
         {"fresh": {"ph": set(), "sha": digest("New")}},
         {"de": {"fresh": {"ph": set(), "sha": digest("Neu")}}}, 0),
        ("english reindented only -> ok (whitespace-normalised)",
         {"greeting": {"ph": set(), "sha": digest("Vela   Voice")}},
         {"de": {"greeting": {"ph": set(), "sha": digest("Vela-Stimme")}}}, 0),
    ]
    bad = 0
    for label, b, l, want in cases:
        got = len(stale_entries(b, l, lock))
        ok = got == want
        bad += 0 if ok else 1
        print(f"  [{'ok' if ok else 'FAIL'}] {label} (stale={got}, want={want})")
    if bad:
        print(f"\nSELFTEST FAILED: {bad} case(s). The staleness rule does not measure what it claims.")
        return 1
    print("\nSelftest OK: the staleness rule fails on an English-only edit and passes otherwise.")
    return 0


def main():
    if "--selftest" in sys.argv:
        return selftest()

    base, locales = read_all()
    if not base:
        print("could not read default strings.xml", file=sys.stderr)
        return 1

    if "--update" in sys.argv:
        with open(LOCK, "w", encoding="utf-8") as fh:
            json.dump(build_lock(base, locales), fh, indent=1, sort_keys=True, ensure_ascii=True)
            fh.write("\n")
        print(f"Recorded {len(base)} keys x {len(locales)} locales into {os.path.basename(LOCK)}.")
        return 0

    problems = []
    for lang in sorted(locales):
        loc = locales[lang]
        for name, entry in base.items():
            if name not in loc:
                problems.append(f"{lang}: missing '{name}'")
            elif loc[name]["ph"] != entry["ph"]:
                problems.append(
                    f"{lang}: '{name}' placeholders {sorted(loc[name]['ph'])} != default {sorted(entry['ph'])}")
    if problems:
        print("Translation check FAILED:\n  " + "\n  ".join(problems))
        print("\nFix: add the key to every values-<lang>/strings.xml, or mark it "
              'translatable="false" in the default file if it is intentionally English-only.')
        return 1

    lock = {}
    if os.path.exists(LOCK):
        lock = json.load(open(LOCK, encoding="utf-8"))
    stale = stale_entries(base, locales, lock)
    if stale:
        keys = sorted({name for _, name in stale})
        print(f"Translation check FAILED: {len(stale)} STALE translation(s) - "
              f"English changed but these did not:")
        for name in keys:
            langs = " ".join(lang for lang, n in stale if n == name)
            print(f"  '{name}' still on the old wording in: {langs}")
        print("\nEditing English text does not update the translations, and every other check here "
              "passes while they disagree - that is how an English-only rename shipped (2026-07-21)."
              "\nFix: update those values-<lang>/strings.xml, then run "
              "`python3 tools/check-translations.py --update` and commit the lock."
              "\nIf a translation is genuinely correct unchanged (a brand name), --update after "
              "confirming it reads right.")
        return 1

    if not lock:
        print(f"Translation check OK: {len(base)} translatable keys present in every locale."
              f"\nNOTE: no {os.path.basename(LOCK)} yet - staleness is unchecked. Run --update to create it.")
        return 0
    print(f"Translation check OK: {len(base)} translatable keys present in every locale, "
          f"placeholders match, no stale translations.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
