#!/usr/bin/env python3
"""Generate the bundled device catalogue (Devices.kt) for Room Browser.

WHY A GENERATOR
    The catalogue is a thousand devices, and every one of them has to be a
    REAL device: a model code that a real handset reports, paired with the
    Android and Chrome versions that handset plausibly runs.  Invented codes
    are worse than useless -- a spoofed identity that no handset ever had is
    exactly the mismatch fingerprinting scripts look for, and shipping one
    would be dishonest about what the app does.

    So the model codes are not written by hand.  They are joined out of
    Google's public "supported devices" list (the Play Console device
    catalogue, storage.googleapis.com/play_public/supported_devices.csv),
    which is the authoritative record of which (brand, marketing name, model
    code) triples actually exist.  What is curated here is the RULE TABLE
    below: which marketing names are 2022-or-later, and which Android/Chrome
    version each generation shipped with.  Everything else is data.

    This mirrors desktop/scripts/gen-filterlist.py: the source data lives in
    a data file, the generated Kotlin is committed, and the generator is run
    by hand rather than at build time (CI must not need the network to build
    the app).

USAGE
    curl -o supported_devices.csv \
        https://storage.googleapis.com/play_public/supported_devices.csv
    python3 android/tools/gen-devices.py supported_devices.csv

    The CSV is UTF-16; UTF-8 is also accepted.  Outputs:
      * android/core/domain/.../model/Devices.kt   (generated, committed)
      * android/tools/devices.tsv                  (the joined data, committed
                                                    so the catalogue can be
                                                    audited and regenerated
                                                    without the CSV)

    --out and --tsv-out redirect those two outputs.  They exist so a change
    to this file can be checked against a scratch copy instead of the
    committed Devices.kt:
        python3 android/tools/gen-devices.py supported_devices.csv \
            --out /tmp/Devices.kt --tsv-out /tmp/devices.tsv
"""

import argparse
import csv
import io
import os
import re
import sys

# --------------------------------------------------------------------------
# Build fingerprints.
#
# Real AOSP build IDs.  A handset reports the build it is running, and these
# are the ones the corresponding Android releases actually shipped; the same
# string appears in the User-Agent of every Chrome on that release, so using
# a real one costs nothing and using a made-up one would be a tell.

BUILD_IDS = {
    "12": "SP1A.210812.016",
    "12L": "SP2A.220305.013",
    "13": "TP1A.220624.014",
    "14": "UP1A.231005.007",
    "15": "AP3A.241105.007",
    "16": "BP2A.250605.031",
}

# The Android release a handset launched on, by launch year.  A device
# reports the OS it shipped with (and keeps reporting it until it is
# upgraded), so pairing a 2022 handset with the newest Android would be as
# wrong as pairing it with the newest Chrome.
ANDROID_BY_YEAR = {
    2022: "12",
    2023: "13",
    2024: "14",
    2025: "15",
}

# Chrome for Android stable.  Chrome updates itself on every one of these
# handsets, so by the time a catalogue generation is curated every entry in
# it runs the SAME current stable - a 2022 handset and a 2025 one report the
# same browser, and pinning each launch year to a different, older build
# would claim a Chrome those devices no longer run.  One current build,
# refreshed with the catalogue (this one: Chrome 156 stable, October 2026;
# the product floor is Chromium 153, never 150 or below).
CHROME_BY_YEAR = {
    2022: "156.0.8078.48",
    2023: "156.0.8078.48",
    2024: "156.0.8078.48",
    2025: "156.0.8078.48",
}

# --------------------------------------------------------------------------
# The rule table.  First match wins.
#
#   (brand, name-pattern, {generation -> launch year})
#
# `brand` is the CSV's "Retail Branding" column, matched case-insensitively
# (the file spells the same maker "Oppo", "OPPO", "Tecno" and "TECNO"
# depending on the row).  The pattern's ONE capture group is the generation
# token, and the dict beside it is what dates that generation.
#
# The year is per GENERATION, never per family range: the whole point of the
# table is that "since 2022" is true.  Collapsing "Galaxy A5x" to one year
# would date the A53 (2022) and the A56 (2025) identically, and a five-year
# spread of handsets claiming the same launch year is exactly the sort of
# incoherence these profiles exist to avoid.  A generation missing from the
# dict is left out of the catalogue.

RULES = [
    # ---- Samsung -----------------------------------------------------
    ("Samsung", r"^Galaxy S(2[2-5])(?: Ultra|\+)? ?(?:5G)?$",
     {"22": 2022, "23": 2023, "24": 2024, "25": 2025}),
    ("Samsung", r"^Galaxy S(2[34]) FE$", {"23": 2023, "24": 2024}),
    ("Samsung", r"^Galaxy Z Flip([4-7])$", {"4": 2022, "5": 2023, "6": 2024, "7": 2025}),
    ("Samsung", r"^Galaxy Z Fold([4-7])$", {"4": 2022, "5": 2023, "6": 2024, "7": 2025}),
    # Galaxy A numbering dates itself from A13 onward; A0x and everything
    # below A13 is 2021 or older and never enters the table.
    ("Samsung", r"^Galaxy A(1[3-7]) ?(?:5G)?$",
     {"13": 2022, "14": 2023, "15": 2023, "16": 2024, "17": 2025}),
    ("Samsung", r"^Galaxy A(2[3-6]) ?(?:5G)?$", {"23": 2022, "24": 2023, "25": 2023, "26": 2025}),
    ("Samsung", r"^Galaxy A(3[3-6]) ?(?:5G)?$", {"33": 2022, "34": 2023, "35": 2024, "36": 2025}),
    ("Samsung", r"^Galaxy A(5[3-6]) ?(?:5G)?$", {"53": 2022, "54": 2023, "55": 2024, "56": 2025}),
    ("Samsung", r"^Galaxy A(7[3-5]) ?(?:5G)?$", {"73": 2022, "74": 2023, "75": 2024}),
    ("Samsung", r"^Galaxy M(1[4-6]) ?(?:5G)?$", {"14": 2022, "15": 2023, "16": 2024}),
    ("Samsung", r"^Galaxy M(3[3-5]) ?(?:5G)?$", {"33": 2022, "34": 2023, "35": 2024}),
    ("Samsung", r"^Galaxy M(5[3-5]) ?(?:5G)?$", {"53": 2022, "54": 2023, "55": 2024}),
    ("Samsung", r"^Galaxy F(1[4-6]) ?(?:5G)?$", {"14": 2022, "15": 2023, "16": 2024}),
    ("Samsung", r"^Galaxy F(5[4-5]) ?(?:5G)?$", {"54": 2023, "55": 2024}),
    ("Samsung", r"^Galaxy Tab S([89]|10)(?:\+| Ultra)?$", {"8": 2022, "9": 2023, "10": 2024}),
    ("Samsung", r"^Galaxy Tab A9(\+)?$", {"9": 2023}),
    ("Samsung", r"^Galaxy XCover([67]) Pro?$", {"6": 2022, "7": 2024}),

    # ---- Google ------------------------------------------------------
    # Pixel 6 and 6 Pro are October 2021 and stay out; 6a is 2022.
    ("Google", r"^Pixel (6a)$", {"6a": 2022}),
    ("Google", r"^Pixel (7|7a|7 Pro|Fold)$", {"7": 2022, "7a": 2023, "7 Pro": 2022, "Fold": 2023}),
    ("Google", r"^Pixel (8|8a|8 Pro)$", {"8": 2023, "8a": 2024, "8 Pro": 2023}),
    ("Google", r"^Pixel (9|9a|9 Pro|9 Pro XL|9 Pro Fold)$",
     {"9": 2024, "9a": 2025, "9 Pro": 2024, "9 Pro XL": 2024, "9 Pro Fold": 2024}),
    ("Google", r"^Pixel (Tablet)$", {"Tablet": 2023}),

    # ---- Xiaomi / Redmi / POCO ---------------------------------------
    ("Xiaomi", r"^(?:Xiaomi )?(1[2-5])(?: Pro| Ultra| Lite|T)?$",
     {"12": 2022, "13": 2023, "14": 2024, "15": 2025}),
    ("Xiaomi", r"^Redmi Note (1[1-4])(?: Pro| Pro\+|S|T|E)? ?(?:5G)?$",
     {"11": 2022, "12": 2022, "13": 2023, "14": 2024}),
    ("Xiaomi", r"^Redmi (1[2-5])(?:C)? ?(?:5G)?$",
     {"12": 2022, "13": 2023, "14": 2024, "15": 2025}),
    ("Xiaomi", r"^Redmi A([3-5])(?:\+)?$", {"3": 2022, "4": 2023, "5": 2024}),
    ("Xiaomi", r"^POCO (X[4-7]|F[4-7]|M[4-7]|C[4-7])(?: Pro| Plus| GT)?$",
     {"X4": 2022, "X5": 2023, "X6": 2024, "X7": 2025, "F4": 2022, "F5": 2023,
      "F6": 2024, "F7": 2025, "M4": 2022, "M5": 2023, "M6": 2024, "M7": 2025,
      "C4": 2022, "C5": 2023, "C6": 2023, "C7": 2024}),
    ("Xiaomi", r"^Redmi (10[AC]?)(?: Pro| 5G)?$", {"10": 2022, "10A": 2022, "10C": 2022}),
    ("Xiaomi", r"^(?:Xiaomi )?Pad ([4-7])(?: Pro)?$",
     {"4": 2022, "5": 2022, "6": 2023, "7": 2024}),
    ("Xiaomi", r"^Redmi Pad (SE)?$", {"": 2022, "SE": 2023}),
    ("Xiaomi", r"^Redmi (K[5-8]0)(?: Pro| Ultra| Gaming)?$",
     {"K50": 2022, "K60": 2023, "K70": 2024, "K80": 2025}),

    # ---- Redmi / POCO / iQOO -----------------------------------------
    # The CSV lists Redmi, POCO and iQOO as makers in their own right, so a
    # rule filed under "Xiaomi" never sees them; each gets its own block.
    ("Redmi", r"^REDMI (1[2-7])C? ?(?:5G)?$",
     {"12": 2022, "13": 2023, "14": 2024, "15": 2025, "17": 2025}),
    ("Redmi", r"^REDMI (A[3-7])(?: Pro)?(?: 5G)?$",
     {"A3": 2022, "A4": 2023, "A5": 2024, "A7": 2025}),
    ("Redmi", r"^REDMI (K[5-9]0)(?: Pro| Ultra| Max| Gaming)?$",
     {"K50": 2022, "K60": 2023, "K70": 2024, "K80": 2025, "K90": 2025}),
    ("Redmi", r"^REDMI Note (1[1-7])(?: Pro\+?|S|T|E|R)?(?: 5G)?$",
     {"11": 2022, "12": 2022, "13": 2023, "14": 2024, "15": 2025, "17": 2025}),
    ("POCO", r"^POCO ([XFMC][0-9]{1,2})(?: Pro| Plus| GT| Ultra)?(?: 5G)?$",
     {"F4": 2022, "F5": 2023, "F6": 2024, "F7": 2025,
      "X4": 2022, "X5": 2023, "X6": 2024, "X7": 2025,
      "M4": 2022, "M5": 2023, "M6": 2024, "M7": 2025,
      "C50": 2022, "C51": 2022, "C55": 2023, "C61": 2023, "C65": 2023,
      "C71": 2023, "C75": 2024, "C81": 2024, "C85": 2025}),
    ("iQOO", r"^iQOO Neo ?(9|1[01])(?: Pro| SE)?(?: 5G)?$",
     {"9": 2023, "10": 2024, "11": 2025}),
    ("iQOO", r"^iQOO (1[2-5])(?: Ultra|R|T)?(?: 5G)?$",
     {"12": 2023, "13": 2024, "15": 2025}),
    ("iQOO", r"^iQOO Z(9|1[01])(?: Lite| Turbo|x|R|i)?(?: 5G)?$",
     {"9": 2023, "10": 2024, "11": 2025}),

    # ---- vivo ---------------------------------------------------------
    # vivo's own marketing names carry the series letter and the generation
    # together (X100, V29, Y36, S17, T2), so one rule per series.
    ("vivo", r"^vivo (X[89]0|X1[0-9]0|X2[0-9]0|X3[0-9]0)(?: Pro\+?| Ultra| Lite)?$",
     {"X80": 2022, "X90": 2022, "X100": 2023, "X200": 2024, "X300": 2025}),
    ("vivo", r"^vivo (V2[5-9]|V[3-5][0-9])(?: Pro| SE)?$",
     {"V25": 2022, "V27": 2023, "V29": 2023, "V30": 2024, "V40": 2024,
      "V50": 2025}),
    ("vivo", r"^vivo (Y[1-4][0-9])(?:s|i| Pro| GT)?$",
     {"Y36": 2023, "Y100": 2024, "Y200": 2024}),
    ("vivo", r"^vivo (S1[5-9]|S[2-5]0)(?:e|t| Pro| Pro mini)?$",
     {"S15": 2022, "S16": 2022, "S17": 2023, "S18": 2023, "S19": 2024,
      "S50": 2025}),
    ("vivo", r"^vivo (T[1-4])(?: Pro| Ultra|x)?$",
     {"T1": 2022, "T2": 2023, "T3": 2024}),

    # ---- OnePlus -----------------------------------------------------
    ("OnePlus", r"^OnePlus (1[0-3])(?: Pro|T|R)? ?(?:5G)?$",
     {"10": 2022, "11": 2023, "12": 2024, "13": 2025}),
    ("OnePlus", r"^OnePlus Nord (CE )?([2-5])(?: Lite|T)? ?(?:5G)?$",
     {"2": 2022, "3": 2023, "4": 2024, "5": 2025}),
    ("OnePlus", r"^OnePlus Nord N(2|3)0 ?(?:5G)?$", {"2": 2022, "3": 2023}),

    # ---- OPPO / vivo / realme ----------------------------------------
    ("Oppo", r"^(?:OPPO )?(Find X[5-8]|Find N[2-5]|Reno ?[7-9]|Reno1[0-3])",
     {"X5": 2022, "X6": 2023, "X7": 2024, "X8": 2024, "N2": 2022, "N3": 2023,
      "N4": 2024, "N5": 2024, "Reno 7": 2022, "Reno 8": 2022, "Reno 9": 2023,
      "Reno10": 2023, "Reno11": 2024, "Reno12": 2024, "Reno13": 2025}),
    ("Oppo", r"^(?:OPPO )?A([5-9][0-9]) ?(?:5G)?$",
     {"57": 2022, "58": 2022, "59": 2022, "77": 2023, "78": 2023, "79": 2024, "98": 2024}),

    # ---- realme / Motorola / Honor / Huawei --------------------------
    # realme puts the generation in a bare number ("realme 11 Pro+") or in
    # the Narzo line ("NARZO 70 5G").
    ("realme", r"^(?:realme )?(GT ?[2-7]|[1-9][0-9])(?: Pro\+?| Pro|T|S)?$",
     {"GT2": 2022, "GT3": 2022, "GT5": 2023, "GT6": 2023, "GT7": 2024,
      "9": 2022, "10": 2022, "11": 2023, "12": 2024, "13": 2025, "14": 2025}),
    ("realme", r"^(?:realme )?NARZO ([5-9][0-9])",
     {"50": 2022, "60": 2023, "70": 2023, "80": 2024}),
    # Motorola names its own launch year — the strongest signal in the file,
    # so it is read directly rather than inferred from a generation number.
    ("Motorola", r"^moto (?:[ge]|edge|razr) ?[0-9]* ?(?:5G)? \((202[2-5])\)$",
     {"2022": 2022, "2023": 2023, "2024": 2024, "2025": 2025}),
    ("Honor", r"^HONOR (?: )?(Magic ?[4-7]|X[5-9][a-z]?|[0-9]{2,3})(?: Lite| Pro| Plus| SE)?$",
     {"Magic4": 2022, "Magic5": 2023, "Magic6": 2024, "Magic7": 2024,
      "X5": 2021, "X6": 2021, "X6a": 2022, "X7": 2022, "X7a": 2022,
      "X8": 2022, "X8a": 2023, "X9": 2023, "X9a": 2023, "X9b": 2024,
      "50": 2021, "70": 2022, "80": 2022, "90": 2023, "100": 2023,
      "200": 2024}),
    # Huawei is deliberately absent.  Since 2019 its handsets ship without
    # Google Play, so the supported-devices list carries no Pura, Mate or
    # nova of the 2022-and-later generations to date them from.

    # ---- Sony / Asus / Nothing ---------------------------------------
    # The roman numeral IS the year for Xperia, whichever model line it is
    # (the 1, 5 and 10 of one generation all launch together).
    ("Sony", r"^Xperia (?:1|5|10) (IV|V|VI|VII)$",
     {"IV": 2022, "V": 2023, "VI": 2024, "VII": 2025}),
    ("Asus", r"^ROG Phone ([6-9])(?: Pro| Ultimate)?$",
     {"6": 2022, "7": 2023, "8": 2024, "9": 2024}),
    ("Asus", r"^Zenfone ([89]|1[0-2])(?: Ultra)?$",
     {"8": 2022, "9": 2022, "10": 2023, "11": 2024, "12": 2025}),
    ("Nothing", r"^Nothing Phone \(([1-3]a?)\)$",
     {"1": 2022, "2": 2023, "2a": 2024, "3": 2025, "3a": 2025}),

    # ---- Transsion / Nokia / TCL -------------------------------------
    ("Infinix", r"^(?:Infinix )?(?:NOTE|HOT|ZERO|GT) ?([1-4][0-9])",
     {"11": 2022, "12": 2022, "13": 2022, "20": 2022, "30": 2023, "40": 2024}),
    ("Tecno", r"^(?:TECNO )?(?:SPARK|CAMON|POVA|PHANTOM) ?([1-3][0-9])",
     {"10": 2022, "20": 2022, "30": 2023, "40": 2024}),
    ("Nokia", r"^Nokia ([GXC][0-9]{2})$",
     {"G11": 2022, "G21": 2022, "G42": 2023, "G60": 2022, "X20": 2022,
      "X30": 2022, "C31": 2022, "C32": 2023}),
    ("TCL", r"^TCL ?([2-6][0-9]{1,2}) ?(?:SE|XE|XL|5G|Pro|Plus|NXTPAPER)*$",
     {"30": 2022, "40": 2023, "50": 2024, "403": 2022, "405": 2022,
      "408": 2023, "505": 2023, "605": 2024}),
]

# The CSV spells the same maker several ways; the catalogue uses one name per
# maker so the brand filter in the app can be a plain list.
CANONICAL_BRAND = {
    "oppo": "OPPO", "vivo": "vivo", "tecno": "TECNO", "itel": "itel",
    "realme": "realme", "oneplus": "OnePlus", "honor": "HONOR",
    "huawei": "Huawei", "nokia": "Nokia", "tcl": "TCL", "zte": "ZTE",
    "infinix": "Infinix", "motorola": "Motorola", "sony": "Sony",
    "asus": "ASUS", "nothing": "Nothing", "google": "Google",
    "samsung": "Samsung", "xiaomi": "Xiaomi",
    "redmi": "Redmi", "poco": "POCO", "iqoo": "iQOO",
    "lenovo": "Lenovo", "blu": "Blu",
}
CURATED = set(CANONICAL_BRAND.values())


# Devices the CSV lists that are not handsets or tablets (TVs, watches, car
# head units) would be wrong here: a browser profile that claims to be a
# television has a UA no phone browser ever sent.  Everything below is
# dropped by name even when a rule above matched.
DENY = re.compile(
    r"\b(TV|Watch|Wear|Band|Router|Camera|Speaker|Projector|Book|Laptop|"
    r"Chromebook|Auto|Car|Head ?Unit|Kindle|Tablet PC)\b",
    re.IGNORECASE,
)

# Tablets stay in the catalogue, but they are not phones: Chrome on an Android
# tablet leaves "Mobile" out of the UA and its own platform token says
# Android, so a tablet entry that borrowed the phone UA would be a string no
# tablet browser ever sent.
TABLET = re.compile(r"\b(Pad|Tab|Tablet|MatePad|MediaPad)\b", re.IGNORECASE)

# Chromium's device-memory hint.  One of 0.5/1/2/4/8 -- a value outside that
# set is itself a fingerprint, so the catalogue only ever emits these.
def device_memory(year):
    return 4 if year <= 2022 else 8


def hardware_concurrency(year):
    return 8 if year >= 2023 else 6


# WebGL strings by chipset family.  The renderer string is what
# WEBGL_debug_renderer_info hands out, and it names the GPU the SoC actually
# has -- an Adreno on an Exynos handset would be a contradiction.
#
# PER MODEL, NOT PER BRAND, and that is deliberate.  An earlier version of
# this file mapped one GPU pair per brand, so every handset of a maker
# carried the same renderer: six distinct strings across the whole
# catalogue, with one of them ("Adreno (TM) 740") on 48.7% of it.  No real
# population of handsets looks like that, so the catalogue fingerprinted the
# generator instead of the devices it claimed to be.  Every model therefore
# states its own GPU below, and a model added later needs its own entry
# rather than inheriting its maker's.
#
# Keyed by (brand, marketing name), the pair a CSV row carries.  The few
# names that were sold on two different SoCs -- the Exynos and Snapdragon
# builds of one Galaxy S, which go to different regions -- are not here at
# all; they are in GPU_BY_CODE, where the model code is what separates them.
# The CSV spells two Lenovo Pad names in Chinese. They are built from their
# code points so this file stays ASCII; the key is still the real name.
_XIAOXIN = chr(0x5C0F) + chr(0x65B0)

GPU_BY_MODEL = {
    # ---- ASUS ------------------------------------------------------
    ("ASUS", "ROG Phone 9"): ("Qualcomm", "Adreno (TM) 830"),
    ("ASUS", "ROG Phone 9 Pro"): ("Qualcomm", "Adreno (TM) 830"),
    ("ASUS", "Zenfone 10"): ("Qualcomm", "Adreno (TM) 740"),
    ("ASUS", "Zenfone 11 Ultra"): ("Qualcomm", "Adreno (TM) 750"),
    ("ASUS", "Zenfone 12 Ultra"): ("Qualcomm", "Adreno (TM) 830"),
    ("ASUS", "Zenfone 8"): ("Qualcomm", "Adreno (TM) 660"),
    ("ASUS", "Zenfone 9"): ("Qualcomm", "Adreno (TM) 730"),
    # ---- Blu -------------------------------------------------------
    ("Blu", "G50 MEGA 2022"): ("Imagination Technologies", "PowerVR Rogue GE8320"),
    ("Blu", "M8L 2022"): ("Imagination Technologies", "PowerVR Rogue GE8320"),
    ("Blu", "Studio X10 2022"): ("Imagination Technologies", "PowerVR Rogue GE8320"),
    ("Blu", "Studio X10L 2022"): ("Imagination Technologies", "PowerVR Rogue GE8320"),
    # ---- Google ----------------------------------------------------
    ("Google", "Pixel 6a"): ("ARM", "Mali-G78 MP20"),
    ("Google", "Pixel 7"): ("ARM", "Mali-G710 MP7"),
    ("Google", "Pixel 7a"): ("ARM", "Mali-G710 MP7"),
    ("Google", "Pixel 8"): ("ARM", "Mali-G715-Immortalis MC10"),
    ("Google", "Pixel 8a"): ("ARM", "Mali-G715-Immortalis MC10"),
    ("Google", "Pixel 9"): ("ARM", "Mali-G715-Immortalis MC10"),
    ("Google", "Pixel 9a"): ("ARM", "Mali-G715-Immortalis MC10"),
    ("Google", "Pixel Fold"): ("ARM", "Mali-G710 MP7"),
    ("Google", "Pixel Tablet"): ("ARM", "Mali-G710 MP7"),
    # ---- HONOR -----------------------------------------------------
    ("HONOR", "HONOR 100"): ("Qualcomm", "Adreno (TM) 720"),
    ("HONOR", "HONOR 100 Pro"): ("Qualcomm", "Adreno (TM) 740"),
    ("HONOR", "HONOR 200"): ("Qualcomm", "Adreno (TM) 720"),
    ("HONOR", "HONOR 200 Pro"): ("Qualcomm", "Adreno (TM) 735"),
    ("HONOR", "HONOR 70"): ("Qualcomm", "Adreno (TM) 642L"),
    ("HONOR", "HONOR 70 Lite"): ("Qualcomm", "Adreno (TM) 619"),
    ("HONOR", "HONOR 90"): ("Qualcomm", "Adreno (TM) 644"),
    ("HONOR", "HONOR 90 Lite"): ("ARM", "Mali-G57 MC2"),
    ("HONOR", "HONOR Magic4 Lite"): ("Qualcomm", "Adreno (TM) 619"),
    ("HONOR", "HONOR Magic4 Pro"): ("Qualcomm", "Adreno (TM) 730"),
    ("HONOR", "HONOR Magic5"): ("Qualcomm", "Adreno (TM) 740"),
    ("HONOR", "HONOR Magic5 Pro"): ("Qualcomm", "Adreno (TM) 740"),
    ("HONOR", "HONOR Magic6"): ("Qualcomm", "Adreno (TM) 750"),
    ("HONOR", "HONOR Magic6 Pro"): ("Qualcomm", "Adreno (TM) 750"),
    ("HONOR", "HONOR Magic7"): ("Qualcomm", "Adreno (TM) 830"),
    ("HONOR", "HONOR Magic7 Lite"): ("Qualcomm", "Adreno (TM) 710"),
    ("HONOR", "HONOR Magic7 Pro"): ("Qualcomm", "Adreno (TM) 830"),
    ("HONOR", "HONOR X6a"): ("Imagination Technologies", "PowerVR Rogue GE8320"),
    ("HONOR", "HONOR X7"): ("Qualcomm", "Adreno (TM) 610"),
    ("HONOR", "HONOR X7a"): ("Imagination Technologies", "PowerVR Rogue GE8320"),
    ("HONOR", "HONOR X8"): ("Qualcomm", "Adreno (TM) 610"),
    ("HONOR", "HONOR X8a"): ("ARM", "Mali-G52 MC2"),
    ("HONOR", "HONOR X9"): ("Qualcomm", "Adreno (TM) 619"),
    # ---- Infinix ---------------------------------------------------
    ("Infinix", "GT 20 Pro"): ("ARM", "Mali-G610 MC6"),
    ("Infinix", "GT 30"): ("ARM", "Mali-G615 MC2"),
    ("Infinix", "GT 30 Pro"): ("ARM", "Mali-G615 MC6"),
    ("Infinix", "HOT 11"): ("ARM", "Mali-G52 MC2"),
    ("Infinix", "HOT 11 2022"): ("ARM", "Mali-G52 MC2"),
    ("Infinix", "HOT 11S"): ("ARM", "Mali-G52 MC2"),
    ("Infinix", "HOT 12i"): ("Imagination Technologies", "PowerVR Rogue GE8320"),
    ("Infinix", "HOT 20 5G"): ("ARM", "Mali-G57 MC2"),
    ("Infinix", "HOT 20 PLAY"): ("Imagination Technologies", "PowerVR Rogue GE8320"),
    ("Infinix", "HOT 20S"): ("ARM", "Mali-G52 MC2"),
    ("Infinix", "HOT 20i"): ("Imagination Technologies", "PowerVR Rogue GE8320"),
    ("Infinix", "HOT 30 5G"): ("ARM", "Mali-G57 MC2"),
    ("Infinix", "Infinix HOT 11S NFC"): ("ARM", "Mali-G52 MC2"),
    ("Infinix", "Infinix HOT 12"): ("Imagination Technologies", "PowerVR Rogue GE8320"),
    ("Infinix", "Infinix HOT 12 PRO"): ("Imagination Technologies", "PowerVR Rogue GE8320"),
    ("Infinix", "Infinix HOT 12 Play"): ("Imagination Technologies", "PowerVR Rogue GE8320"),
    ("Infinix", "Infinix HOT 12 Play NFC"): ("Imagination Technologies", "PowerVR Rogue GE8320"),
    ("Infinix", "Infinix HOT 20"): ("ARM", "Mali-G52 MC2"),
    ("Infinix", "Infinix HOT 30 PLAY"): ("ARM", "Mali-G57 MP1"),
    ("Infinix", "Infinix HOT 30i"): ("ARM", "Mali-G57 MP1"),
    ("Infinix", "Infinix HOT 40"): ("ARM", "Mali-G57 MC2"),
    ("Infinix", "Infinix HOT 40 Pro"): ("ARM", "Mali-G57 MC2"),
    ("Infinix", "Infinix HOT 40i"): ("ARM", "Mali-G57 MP1"),
    ("Infinix", "Infinix NOTE 11"): ("ARM", "Mali-G52 MC2"),
    ("Infinix", "Infinix NOTE 11i"): ("ARM", "Mali-G52 MC2"),
    ("Infinix", "Infinix NOTE 12"): ("ARM", "Mali-G52 MC2"),
    ("Infinix", "Infinix NOTE 12 2023"): ("ARM", "Mali-G52 MC2"),
    ("Infinix", "Infinix NOTE 12 5G"): ("ARM", "Mali-G57 MC2"),
    ("Infinix", "Infinix NOTE 12 PRO"): ("ARM", "Mali-G52 MC2"),
    ("Infinix", "Infinix NOTE 12 Pro 5G"): ("ARM", "Mali-G57 MC2"),
    ("Infinix", "Infinix NOTE 12i"): ("ARM", "Mali-G52 MC2"),
    ("Infinix", "Infinix NOTE 30"): ("ARM", "Mali-G57 MC2"),
    ("Infinix", "Infinix NOTE 30 5G"): ("ARM", "Mali-G68 MC4"),
    ("Infinix", "Infinix NOTE 40"): ("ARM", "Mali-G57 MC2"),
    ("Infinix", "Infinix NOTE 40 5G"): ("ARM", "Mali-G68 MC4"),
    ("Infinix", "Infinix NOTE 40 Pro"): ("ARM", "Mali-G57 MC2"),
    ("Infinix", "Infinix NOTE 40 Pro 5G"): ("ARM", "Mali-G68 MC4"),
    ("Infinix", "Infinix NOTE 40S"): ("ARM", "Mali-G57 MC2"),
    ("Infinix", "Infinix NOTE 40X 5G"): ("ARM", "Mali-G57 MC2"),
    ("Infinix", "Infinix ZERO 20"): ("ARM", "Mali-G57 MC2"),
    ("Infinix", "Infinix ZERO 30"): ("ARM", "Mali-G57 MC2"),
    ("Infinix", "Infinix ZERO 40"): ("ARM", "Mali-G57 MC2"),
    ("Infinix", "Infinix ZERO 40 5G"): ("ARM", "Mali-G610 MC6"),
    ("Infinix", "NOTE 11 Pro"): ("ARM", "Mali-G57 MC2"),
    ("Infinix", "NOTE 11S"): ("ARM", "Mali-G57 MC2"),
    ("Infinix", "NOTE 12"): ("ARM", "Mali-G52 MC2"),
    ("Infinix", "NOTE 12 VIP"): ("ARM", "Mali-G57 MC2"),
    ("Infinix", "NOTE 12i 2022"): ("ARM", "Mali-G52 MC2"),
    ("Infinix", "NOTE 30"): ("ARM", "Mali-G57 MC2"),
    ("Infinix", "NOTE 30 Pro"): ("ARM", "Mali-G57 MC2"),
    ("Infinix", "NOTE 30 VIP"): ("ARM", "Mali-G77 MC9"),
    ("Infinix", "NOTE 30i"): ("ARM", "Mali-G57 MC2"),
    ("Infinix", "NOTE 40 Pro+ 5G"): ("ARM", "Mali-G610 MC6"),
    ("Infinix", "ZERO 30 5G"): ("ARM", "Mali-G77 MC9"),
    ("Infinix", "ZERO 5G 2023"): ("ARM", "Mali-G68 MC4"),
    # ---- Lenovo ----------------------------------------------------
    ("Lenovo", "Lenovo Tab M8 (4th Gen) 2024"): ("Imagination Technologies", "PowerVR Rogue GE8320"),
    # The CSV spells these two Lenovo Pad names in Chinese. The name is
    # built from its code points so this file stays ASCII; the key is still
    # the real name, and that is what the catalogue carries.
    ("Lenovo", _XIAOXIN + "Pad Plus 2023"): ("Qualcomm", "Adreno (TM) 650"),
    ("Lenovo", _XIAOXIN + "Pad Pro 2022"): ("Qualcomm", "Adreno (TM) 650"),
    # ---- Motorola --------------------------------------------------
    ("Motorola", "moto g - 2025"): ("Qualcomm", "Adreno (TM) 619"),
    ("Motorola", "moto g 5G (2022)"): ("ARM", "Mali-G57 MC2"),
    ("Motorola", "moto g 5G - 2023"): ("Qualcomm", "Adreno (TM) 619"),
    ("Motorola", "moto g 5G - 2024"): ("Qualcomm", "Adreno (TM) 619"),
    ("Motorola", "moto g play - 2024"): ("Qualcomm", "Adreno (TM) 610"),
    ("Motorola", "moto g power (2022)"): ("Imagination Technologies", "PowerVR Rogue GE8320"),
    ("Motorola", "moto g power - 2025"): ("ARM", "Mali-G57 MC2"),
    ("Motorola", "moto g power 5G - 2023"): ("ARM", "Mali-G68 MC4"),
    ("Motorola", "moto g stylus (2022)"): ("ARM", "Mali-G52 MC2"),
    ("Motorola", "moto g stylus (2023)"): ("ARM", "Mali-G52 MC2"),
    ("Motorola", "moto g stylus 5G (2022)"): ("Qualcomm", "Adreno (TM) 619"),
    ("Motorola", "moto g stylus 5G - 2023"): ("Qualcomm", "Adreno (TM) 619"),
    ("Motorola", "moto g stylus 5G - 2024"): ("Qualcomm", "Adreno (TM) 710"),
    ("Motorola", "moto g stylus 5G - 2025"): ("Qualcomm", "Adreno (TM) 710"),
    ("Motorola", "motorola edge (2022)"): ("ARM", "Mali-G77 MC9"),
    ("Motorola", "motorola edge 2023"): ("ARM", "Mali-G57 MC2"),
    ("Motorola", "motorola edge 2024"): ("Qualcomm", "Adreno (TM) 710"),
    ("Motorola", "motorola edge 2025"): ("ARM", "Mali-G615 MC2"),
    ("Motorola", "motorola edge plus (2022)"): ("Qualcomm", "Adreno (TM) 730"),
    ("Motorola", "motorola edge plus 2023"): ("Qualcomm", "Adreno (TM) 740"),
    ("Motorola", "motorola edge plus 5G UW (2022)"): ("Qualcomm", "Adreno (TM) 730"),
    ("Motorola", "motorola razr 2022"): ("Qualcomm", "Adreno (TM) 730"),
    ("Motorola", "motorola razr 2023"): ("Qualcomm", "Adreno (TM) 644"),
    ("Motorola", "motorola razr 2024"): ("ARM", "Mali-G615 MC2"),
    ("Motorola", "motorola razr 2025"): ("ARM", "Mali-G615 MC2"),
    ("Motorola", "motorola razr plus 2023"): ("Qualcomm", "Adreno (TM) 730"),
    ("Motorola", "motorola razr plus 2024"): ("Qualcomm", "Adreno (TM) 735"),
    ("Motorola", "motorola razr plus 2025"): ("Qualcomm", "Adreno (TM) 735"),
    ("Motorola", "motorola razr ultra 2025"): ("Qualcomm", "Adreno (TM) 830"),
    # ---- Nokia -----------------------------------------------------
    ("Nokia", "Nokia C31"): ("Imagination Technologies", "PowerVR Rogue GE8322"),
    ("Nokia", "Nokia C32"): ("Imagination Technologies", "PowerVR Rogue GE8322"),
    ("Nokia", "Nokia G11"): ("ARM", "Mali-G57 MP1"),
    ("Nokia", "Nokia G21"): ("ARM", "Mali-G57 MP1"),
    ("Nokia", "Nokia X20"): ("Qualcomm", "Adreno (TM) 619"),
    # ---- Nothing ---------------------------------------------------
    ("Nothing", "Nothing Phone (1)"): ("Qualcomm", "Adreno (TM) 642L"),
    ("Nothing", "Nothing Phone (2)"): ("Qualcomm", "Adreno (TM) 730"),
    ("Nothing", "Nothing Phone (2a)"): ("ARM", "Mali-G610 MC4"),
    ("Nothing", "Nothing Phone (3)"): ("Qualcomm", "Adreno (TM) 825"),
    ("Nothing", "Nothing Phone (3a)"): ("Qualcomm", "Adreno (TM) 810"),
    # ---- OPPO ------------------------------------------------------
    ("OPPO", "A57"): ("Imagination Technologies", "PowerVR Rogue GE8320"),
    ("OPPO", "A58"): ("ARM", "Mali-G57 MC2"),
    ("OPPO", "A59"): ("ARM", "Mali-G52 MC2"),
    ("OPPO", "A59 5G"): ("ARM", "Mali-G57 MC2"),
    ("OPPO", "A77"): ("ARM", "Mali-G52 MC2"),
    ("OPPO", "A77 5G"): ("ARM", "Mali-G57 MC2"),
    ("OPPO", "A78"): ("ARM", "Mali-G52 MC2"),
    ("OPPO", "A78 5G"): ("ARM", "Mali-G57 MC2"),
    ("OPPO", "A79"): ("ARM", "Mali-G52 MC2"),
    ("OPPO", "A79 5G"): ("ARM", "Mali-G57 MC2"),
    ("OPPO", "A98 5G"): ("Qualcomm", "Adreno (TM) 619"),
    ("OPPO", "OPPO Reno10 5G"): ("ARM", "Mali-G68 MC4"),
    ("OPPO", "OPPO Reno10 Pro 5G"): ("ARM", "Mali-G610 MC6"),
    ("OPPO", "OPPO Reno10 Pro+ 5G"): ("Qualcomm", "Adreno (TM) 730"),
    ("OPPO", "Reno10 5G"): ("ARM", "Mali-G68 MC4"),
    ("OPPO", "Reno10 Pro 5G"): ("ARM", "Mali-G610 MC6"),
    ("OPPO", "Reno10 Pro+ 5G"): ("Qualcomm", "Adreno (TM) 730"),
    ("OPPO", "Reno11"): ("ARM", "Mali-G68 MC4"),
    ("OPPO", "Reno11 A"): ("ARM", "Mali-G68 MC4"),
    ("OPPO", "Reno11 F 5G"): ("ARM", "Mali-G68 MC4"),
    ("OPPO", "Reno11 Pro"): ("ARM", "Mali-G610 MC6"),
    ("OPPO", "Reno12"): ("ARM", "Mali-G615 MC2"),
    ("OPPO", "Reno12 5G"): ("ARM", "Mali-G615 MC2"),
    ("OPPO", "Reno12 F"): ("ARM", "Mali-G615 MC2"),
    ("OPPO", "Reno12 F 5G"): ("ARM", "Mali-G615 MC2"),
    ("OPPO", "Reno12 Pro"): ("ARM", "Mali-G615 MC2"),
    ("OPPO", "Reno12 Pro 5G"): ("ARM", "Mali-G615 MC2"),
    ("OPPO", "Reno13"): ("ARM", "Mali-G615 MC6"),
    ("OPPO", "Reno13 5G"): ("ARM", "Mali-G615 MC6"),
    ("OPPO", "Reno13 A"): ("ARM", "Mali-G615 MC2"),
    ("OPPO", "Reno13 F"): ("ARM", "Mali-G615 MC2"),
    ("OPPO", "Reno13 F 5G"): ("ARM", "Mali-G615 MC2"),
    ("OPPO", "Reno13 Pro"): ("ARM", "Mali-G615 MC6"),
    ("OPPO", "Reno13 Pro 5G"): ("ARM", "Mali-G615 MC6"),
    # ---- OnePlus ---------------------------------------------------
    ("OnePlus", "OnePlus 10 Pro"): ("Qualcomm", "Adreno (TM) 730"),
    ("OnePlus", "OnePlus 10 Pro 5G"): ("Qualcomm", "Adreno (TM) 730"),
    ("OnePlus", "OnePlus 10R 5G"): ("ARM", "Mali-G610 MC6"),
    ("OnePlus", "OnePlus 10T 5G"): ("Qualcomm", "Adreno (TM) 730"),
    ("OnePlus", "OnePlus 11 5G"): ("Qualcomm", "Adreno (TM) 740"),
    ("OnePlus", "OnePlus 11R 5G"): ("Qualcomm", "Adreno (TM) 730"),
    ("OnePlus", "OnePlus 12"): ("Qualcomm", "Adreno (TM) 750"),
    ("OnePlus", "OnePlus 12R"): ("Qualcomm", "Adreno (TM) 740"),
    ("OnePlus", "OnePlus 13"): ("Qualcomm", "Adreno (TM) 830"),
    ("OnePlus", "OnePlus 13R"): ("Qualcomm", "Adreno (TM) 750"),
    ("OnePlus", "OnePlus 13T"): ("Qualcomm", "Adreno (TM) 830"),
    ("OnePlus", "OnePlus Nord 2T 5G"): ("ARM", "Mali-G77 MC9"),
    ("OnePlus", "OnePlus Nord 3 5G"): ("ARM", "Mali-G710 MC10"),
    ("OnePlus", "OnePlus Nord 4"): ("Qualcomm", "Adreno (TM) 732"),
    ("OnePlus", "OnePlus Nord 5"): ("Qualcomm", "Adreno (TM) 735"),
    ("OnePlus", "OnePlus Nord N20 5G"): ("Qualcomm", "Adreno (TM) 619"),
    ("OnePlus", "OnePlus Nord N30 5G"): ("Qualcomm", "Adreno (TM) 619"),
    # ---- POCO ------------------------------------------------------
    ("POCO", "POCO C50"): ("Imagination Technologies", "PowerVR Rogue GE8320"),
    ("POCO", "POCO C51"): ("Imagination Technologies", "PowerVR Rogue GE8320"),
    ("POCO", "POCO C55"): ("ARM", "Mali-G52 MC2"),
    ("POCO", "POCO C61"): ("ARM", "Mali-G52 MC2"),
    ("POCO", "POCO C65"): ("ARM", "Mali-G52 MC2"),
    ("POCO", "POCO C71"): ("ARM", "Mali-G52 MC2"),
    ("POCO", "POCO C75"): ("ARM", "Mali-G52 MC2"),
    ("POCO", "POCO C75 5G"): ("ARM", "Mali-G52 MC2"),
    ("POCO", "POCO C81"): ("ARM", "Mali-G52 MC2"),
    ("POCO", "POCO C81 Pro"): ("ARM", "Mali-G52 MC2"),
    ("POCO", "POCO C85"): ("ARM", "Mali-G52 MC2"),
    ("POCO", "POCO C85 5G"): ("ARM", "Mali-G52 MC2"),
    ("POCO", "POCO F4"): ("Qualcomm", "Adreno (TM) 650"),
    ("POCO", "POCO F4 GT"): ("Qualcomm", "Adreno (TM) 730"),
    ("POCO", "POCO F5"): ("Qualcomm", "Adreno (TM) 725"),
    ("POCO", "POCO F6"): ("Qualcomm", "Adreno (TM) 735"),
    ("POCO", "POCO F6 Pro"): ("Qualcomm", "Adreno (TM) 740"),
    ("POCO", "POCO M4 5G"): ("ARM", "Mali-G57 MC2"),
    ("POCO", "POCO M4 Pro"): ("ARM", "Mali-G57 MC2"),
    ("POCO", "POCO M4 Pro 5G"): ("ARM", "Mali-G57 MC2"),
    ("POCO", "POCO M5"): ("ARM", "Mali-G57 MC2"),
    ("POCO", "POCO M6"): ("Qualcomm", "Adreno (TM) 610"),
    ("POCO", "POCO M6 Plus 5G"): ("ARM", "Mali-G57 MC2"),
    ("POCO", "POCO M6 Pro"): ("ARM", "Mali-G57 MC2"),
    ("POCO", "POCO M6 Pro 5G"): ("ARM", "Mali-G57 MC2"),
    ("POCO", "POCO M7"): ("ARM", "Mali-G615 MC2"),
    ("POCO", "POCO M7 5G"): ("ARM", "Mali-G615 MC2"),
    ("POCO", "POCO M7 Plus 5G"): ("ARM", "Mali-G57 MC2"),
    ("POCO", "POCO M7 Pro 5G"): ("ARM", "Mali-G57 MC2"),
    ("POCO", "POCO X4 GT"): ("ARM", "Mali-G610 MC6"),
    ("POCO", "POCO X4 Pro 5G"): ("Qualcomm", "Adreno (TM) 619"),
    ("POCO", "POCO X5 Pro 5G"): ("Qualcomm", "Adreno (TM) 642L"),
    ("POCO", "POCO X6 5G"): ("Qualcomm", "Adreno (TM) 710"),
    ("POCO", "POCO X6 Pro 5G"): ("ARM", "Mali-G615 MC6"),
    ("POCO", "POCO X7"): ("ARM", "Mali-G615 MC2"),
    ("POCO", "POCO X7 Pro"): ("ARM", "Mali-G720-Immortalis MC7"),
    # ---- Redmi -----------------------------------------------------
    ("Redmi", "REDMI 15C 5G"): ("ARM", "Mali-G57 MC2"),
    ("Redmi", "REDMI 17 5G"): ("ARM", "Mali-G57 MC2"),
    ("Redmi", "REDMI 17C 5G"): ("ARM", "Mali-G57 MC2"),
    ("Redmi", "REDMI A7"): ("ARM", "Mali-G52 MC2"),
    ("Redmi", "REDMI A7 Pro"): ("ARM", "Mali-G52 MC2"),
    ("Redmi", "REDMI A7 Pro 5G"): ("ARM", "Mali-G57 MP1"),
    ("Redmi", "REDMI K80"): ("Qualcomm", "Adreno (TM) 750"),
    ("Redmi", "REDMI K80 Pro"): ("Qualcomm", "Adreno (TM) 830"),
    ("Redmi", "REDMI K80 Ultra"): ("ARM", "Mali-G925-Immortalis MC12"),
    ("Redmi", "REDMI K90"): ("Qualcomm", "Adreno (TM) 840"),
    ("Redmi", "REDMI Note 15"): ("ARM", "Mali-G57 MC2"),
    ("Redmi", "REDMI Note 15 5G"): ("ARM", "Mali-G615 MC2"),
    ("Redmi", "REDMI Note 15 Pro"): ("ARM", "Mali-G57 MC2"),
    ("Redmi", "REDMI Note 15 Pro 5G"): ("Qualcomm", "Adreno (TM) 710"),
    ("Redmi", "REDMI Note 15 Pro+"): ("Qualcomm", "Adreno (TM) 710"),
    ("Redmi", "REDMI Note 15R"): ("ARM", "Mali-G57 MC2"),
    ("Redmi", "REDMI Note 17"): ("ARM", "Mali-G57 MC2"),
    ("Redmi", "REDMI Note 17 5G"): ("ARM", "Mali-G615 MC2"),
    ("Redmi", "REDMI Note 17 Pro"): ("ARM", "Mali-G57 MC2"),
    ("Redmi", "REDMI Note 17 Pro 5G"): ("Qualcomm", "Adreno (TM) 710"),
    ("Redmi", "Redmi 10 2022"): ("ARM", "Mali-G52 MC2"),
    # ---- Samsung ---------------------------------------------------
    ("Samsung", "Galaxy A13"): ("ARM", "Mali-G52 MP1"),
    ("Samsung", "Galaxy A13 5G"): ("ARM", "Mali-G57 MC2"),
    ("Samsung", "Galaxy A14"): ("ARM", "Mali-G52 MP1"),
    ("Samsung", "Galaxy A14 5G"): ("ARM", "Mali-G57 MC2"),
    ("Samsung", "Galaxy A15"): ("ARM", "Mali-G57 MC2"),
    ("Samsung", "Galaxy A15 5G"): ("ARM", "Mali-G57 MC2"),
    ("Samsung", "Galaxy A16"): ("ARM", "Mali-G57 MC2"),
    ("Samsung", "Galaxy A16 5G"): ("ARM", "Mali-G57 MC2"),
    ("Samsung", "Galaxy A17"): ("ARM", "Mali-G57 MC2"),
    ("Samsung", "Galaxy A17 5G"): ("ARM", "Mali-G57 MC2"),
    ("Samsung", "Galaxy A23"): ("Qualcomm", "Adreno (TM) 610"),
    ("Samsung", "Galaxy A23 5G"): ("Qualcomm", "Adreno (TM) 619"),
    ("Samsung", "Galaxy A24"): ("ARM", "Mali-G57 MC2"),
    ("Samsung", "Galaxy A25 5G"): ("ARM", "Mali-G68 MC4"),
    ("Samsung", "Galaxy A26 5G"): ("ARM", "Mali-G68 MC4"),
    ("Samsung", "Galaxy A33 5G"): ("ARM", "Mali-G68 MC4"),
    ("Samsung", "Galaxy A34 5G"): ("ARM", "Mali-G68 MC4"),
    ("Samsung", "Galaxy A35 5G"): ("ARM", "Mali-G68 MC4"),
    ("Samsung", "Galaxy A36 5G"): ("Qualcomm", "Adreno (TM) 710"),
    ("Samsung", "Galaxy A53 5G"): ("ARM", "Mali-G68 MC4"),
    ("Samsung", "Galaxy A54 5G"): ("ARM", "Mali-G68 MC4"),
    ("Samsung", "Galaxy A55 5G"): ("Samsung", "Xclipse 530"),
    ("Samsung", "Galaxy A56 5G"): ("Samsung", "Xclipse 540"),
    ("Samsung", "Galaxy A73 5G"): ("Qualcomm", "Adreno (TM) 642L"),
    ("Samsung", "Galaxy F14"): ("ARM", "Mali-G52 MP1"),
    ("Samsung", "Galaxy F14 5G"): ("ARM", "Mali-G57 MC2"),
    ("Samsung", "Galaxy F15 5G"): ("ARM", "Mali-G57 MC2"),
    ("Samsung", "Galaxy F16 5G"): ("ARM", "Mali-G57 MC2"),
    ("Samsung", "Galaxy F54 5G"): ("ARM", "Mali-G68 MC4"),
    ("Samsung", "Galaxy F55 5G"): ("Qualcomm", "Adreno (TM) 644"),
    ("Samsung", "Galaxy M14"): ("ARM", "Mali-G52 MP1"),
    ("Samsung", "Galaxy M14 5G"): ("ARM", "Mali-G57 MC2"),
    ("Samsung", "Galaxy M15 5G"): ("ARM", "Mali-G57 MC2"),
    ("Samsung", "Galaxy M16 5G"): ("ARM", "Mali-G57 MC2"),
    ("Samsung", "Galaxy M33 5G"): ("ARM", "Mali-G68 MC4"),
    ("Samsung", "Galaxy M34 5G"): ("ARM", "Mali-G68 MC4"),
    ("Samsung", "Galaxy M35 5G"): ("ARM", "Mali-G68 MC4"),
    ("Samsung", "Galaxy M53 5G"): ("ARM", "Mali-G68 MC4"),
    ("Samsung", "Galaxy M54 5G"): ("Qualcomm", "Adreno (TM) 660"),
    ("Samsung", "Galaxy M55 5G"): ("Qualcomm", "Adreno (TM) 644"),
    ("Samsung", "Galaxy S23"): ("Qualcomm", "Adreno (TM) 740"),
    ("Samsung", "Galaxy S23 Ultra"): ("Qualcomm", "Adreno (TM) 740"),
    ("Samsung", "Galaxy S23+"): ("Qualcomm", "Adreno (TM) 740"),
    ("Samsung", "Galaxy S24 FE"): ("Samsung", "Xclipse 940"),
    ("Samsung", "Galaxy S24 Ultra"): ("Qualcomm", "Adreno (TM) 750"),
    ("Samsung", "Galaxy S25"): ("Qualcomm", "Adreno (TM) 830"),
    ("Samsung", "Galaxy S25 Ultra"): ("Qualcomm", "Adreno (TM) 830"),
    ("Samsung", "Galaxy S25+"): ("Qualcomm", "Adreno (TM) 830"),
    ("Samsung", "Galaxy Tab S10 Ultra"): ("ARM", "Mali-G720-Immortalis MC12"),
    ("Samsung", "Galaxy Tab S10+"): ("ARM", "Mali-G720-Immortalis MC12"),
    ("Samsung", "Galaxy Tab S8"): ("Qualcomm", "Adreno (TM) 730"),
    ("Samsung", "Galaxy Tab S8 Ultra"): ("Qualcomm", "Adreno (TM) 730"),
    ("Samsung", "Galaxy Tab S8+"): ("Qualcomm", "Adreno (TM) 730"),
    ("Samsung", "Galaxy Tab S9"): ("Qualcomm", "Adreno (TM) 740"),
    ("Samsung", "Galaxy Tab S9 Ultra"): ("Qualcomm", "Adreno (TM) 740"),
    ("Samsung", "Galaxy Tab S9+"): ("Qualcomm", "Adreno (TM) 740"),
    ("Samsung", "Galaxy XCover6 Pro"): ("Qualcomm", "Adreno (TM) 642L"),
    ("Samsung", "Galaxy XCover7 Pro"): ("Qualcomm", "Adreno (TM) 710"),
    ("Samsung", "Galaxy Z Flip4"): ("Qualcomm", "Adreno (TM) 730"),
    ("Samsung", "Galaxy Z Flip5"): ("Qualcomm", "Adreno (TM) 740"),
    ("Samsung", "Galaxy Z Flip6"): ("Qualcomm", "Adreno (TM) 750"),
    ("Samsung", "Galaxy Z Flip7"): ("Qualcomm", "Adreno (TM) 830"),
    ("Samsung", "Galaxy Z Fold4"): ("Qualcomm", "Adreno (TM) 730"),
    ("Samsung", "Galaxy Z Fold5"): ("Qualcomm", "Adreno (TM) 740"),
    ("Samsung", "Galaxy Z Fold6"): ("Qualcomm", "Adreno (TM) 750"),
    ("Samsung", "Galaxy Z Fold7"): ("Qualcomm", "Adreno (TM) 830"),
    # ---- Sony ------------------------------------------------------
    ("Sony", "Xperia 1 IV"): ("Qualcomm", "Adreno (TM) 730"),
    ("Sony", "Xperia 1 V"): ("Qualcomm", "Adreno (TM) 740"),
    ("Sony", "Xperia 1 VI"): ("Qualcomm", "Adreno (TM) 750"),
    ("Sony", "Xperia 1 VII"): ("Qualcomm", "Adreno (TM) 830"),
    ("Sony", "Xperia 10 IV"): ("Qualcomm", "Adreno (TM) 619"),
    ("Sony", "Xperia 10 V"): ("Qualcomm", "Adreno (TM) 619"),
    ("Sony", "Xperia 10 VI"): ("Qualcomm", "Adreno (TM) 710"),
    ("Sony", "Xperia 10 VII"): ("Qualcomm", "Adreno (TM) 710"),
    ("Sony", "Xperia 5 IV"): ("Qualcomm", "Adreno (TM) 730"),
    ("Sony", "Xperia 5 V"): ("Qualcomm", "Adreno (TM) 740"),
    # ---- TCL -------------------------------------------------------
    ("TCL", "TAB 10s 2022"): ("Imagination Technologies", "PowerVR Rogue GE8320"),
    ("TCL", "TAB 10s 4G 2022"): ("Imagination Technologies", "PowerVR Rogue GE8320"),
    ("TCL", "TCL 30"): ("Imagination Technologies", "PowerVR Rogue GE8320"),
    ("TCL", "TCL 30 5G"): ("ARM", "Mali-G57 MC2"),
    ("TCL", "TCL 30 SE"): ("Imagination Technologies", "PowerVR Rogue GE8320"),
    ("TCL", "TCL 30 XL"): ("Imagination Technologies", "PowerVR Rogue GE8320"),
    ("TCL", "TCL 40 NXTPAPER"): ("ARM", "Mali-G57 MC2"),
    ("TCL", "TCL 40 SE"): ("ARM", "Mali-G52 MC2"),
    ("TCL", "TCL 403"): ("Imagination Technologies", "PowerVR Rogue GE8320"),
    ("TCL", "TCL 405"): ("Imagination Technologies", "PowerVR Rogue GE8320"),
    ("TCL", "TCL 408"): ("ARM", "Mali-G52 MC2"),
    ("TCL", "TCL 40XL"): ("ARM", "Mali-G52 MC2"),
    ("TCL", "TCL 50 5G"): ("ARM", "Mali-G57 MC2"),
    ("TCL", "TCL 50 SE"): ("ARM", "Mali-G57 MC2"),
    ("TCL", "TCL 505"): ("ARM", "Mali-G52 MC2"),
    ("TCL", "TCL 605"): ("Qualcomm", "Adreno (TM) 613"),
    # ---- TECNO -----------------------------------------------------
    ("TECNO", "CAMON 20 Pro"): ("ARM", "Mali-G52 MC2"),
    ("TECNO", "CAMON 20 Pro 5G"): ("ARM", "Mali-G77 MC9"),
    ("TECNO", "SPARK 10 5G"): ("ARM", "Mali-G57 MC2"),
    ("TECNO", "SPARK Go 2023"): ("Imagination Technologies", "PowerVR Rogue GE8320"),
    ("TECNO", "SPARK Go 2024"): ("Imagination Technologies", "PowerVR Rogue GE8320"),
    ("TECNO", "TECNO CAMON 20"): ("ARM", "Mali-G52 MC2"),
    ("TECNO", "TECNO CAMON 20 Premier 5G"): ("ARM", "Mali-G77 MC9"),
    ("TECNO", "TECNO CAMON 20s Pro 5G"): ("ARM", "Mali-G77 MC9"),
    ("TECNO", "TECNO CAMON 30"): ("ARM", "Mali-G57 MC2"),
    ("TECNO", "TECNO CAMON 30 5G"): ("ARM", "Mali-G68 MC4"),
    ("TECNO", "TECNO CAMON 30 Premier 5G"): ("ARM", "Mali-G610 MC6"),
    ("TECNO", "TECNO CAMON 30 Pro 5G"): ("ARM", "Mali-G610 MC6"),
    ("TECNO", "TECNO CAMON 30S"): ("ARM", "Mali-G57 MC2"),
    ("TECNO", "TECNO CAMON 30S Pro"): ("ARM", "Mali-G57 MC2"),
    ("TECNO", "TECNO CAMON 30T"): ("ARM", "Mali-G57 MC2"),
    ("TECNO", "TECNO SPARK 10"): ("ARM", "Mali-G52 MC2"),
    ("TECNO", "TECNO SPARK 10 Pro"): ("ARM", "Mali-G52 MC2"),
    ("TECNO", "TECNO SPARK 10C"): ("ARM", "Mali-G52 MC2"),
    ("TECNO", "TECNO SPARK 20"): ("ARM", "Mali-G52 MC2"),
    ("TECNO", "TECNO SPARK 20 Pro"): ("ARM", "Mali-G57 MC2"),
    ("TECNO", "TECNO SPARK 20 Pro 5G"): ("ARM", "Mali-G57 MC2"),
    ("TECNO", "TECNO SPARK 20 Pro+"): ("ARM", "Mali-G57 MC2"),
    ("TECNO", "TECNO SPARK 20C"): ("ARM", "Mali-G52 MC2"),
    ("TECNO", "TECNO SPARK 30"): ("ARM", "Mali-G52 MC2"),
    ("TECNO", "TECNO SPARK 30 5G"): ("ARM", "Mali-G57 MC2"),
    ("TECNO", "TECNO SPARK 30 Pro"): ("ARM", "Mali-G57 MC2"),
    ("TECNO", "TECNO SPARK 30C"): ("ARM", "Mali-G52 MC2"),
    ("TECNO", "TECNO SPARK 30C 5G"): ("ARM", "Mali-G57 MC2"),
    ("TECNO", "TECNO SPARK Go 2022"): ("Imagination Technologies", "PowerVR Rogue GE8320"),
    ("TECNO", "TECNO SPARK Go 2024"): ("Imagination Technologies", "PowerVR Rogue GE8320"),
    # ---- Xiaomi ----------------------------------------------------
    ("Xiaomi", "Xiaomi 12"): ("Qualcomm", "Adreno (TM) 730"),
    ("Xiaomi", "Xiaomi 12 Lite"): ("Qualcomm", "Adreno (TM) 642L"),
    ("Xiaomi", "Xiaomi 12 Pro"): ("Qualcomm", "Adreno (TM) 730"),
    ("Xiaomi", "Xiaomi 12T"): ("ARM", "Mali-G610 MC6"),
    ("Xiaomi", "Xiaomi 13"): ("Qualcomm", "Adreno (TM) 740"),
    ("Xiaomi", "Xiaomi 13 Lite"): ("Qualcomm", "Adreno (TM) 644"),
    ("Xiaomi", "Xiaomi 13 Pro"): ("Qualcomm", "Adreno (TM) 740"),
    ("Xiaomi", "Xiaomi 13 Ultra"): ("Qualcomm", "Adreno (TM) 740"),
    ("Xiaomi", "Xiaomi 13T"): ("ARM", "Mali-G610 MC6"),
    ("Xiaomi", "Xiaomi 14"): ("Qualcomm", "Adreno (TM) 750"),
    ("Xiaomi", "Xiaomi 14 Pro"): ("Qualcomm", "Adreno (TM) 750"),
    ("Xiaomi", "Xiaomi 14 Ultra"): ("Qualcomm", "Adreno (TM) 750"),
    ("Xiaomi", "Xiaomi 14T"): ("ARM", "Mali-G615 MC6"),
    ("Xiaomi", "Xiaomi 15"): ("Qualcomm", "Adreno (TM) 830"),
    ("Xiaomi", "Xiaomi 15 Pro"): ("Qualcomm", "Adreno (TM) 830"),
    ("Xiaomi", "Xiaomi 15 Ultra"): ("Qualcomm", "Adreno (TM) 830"),
    ("Xiaomi", "Xiaomi 15T"): ("ARM", "Mali-G720-Immortalis MC7"),
    ("Xiaomi", "Xiaomi Pad 5"): ("Qualcomm", "Adreno (TM) 640"),
    ("Xiaomi", "Xiaomi Pad 5 Pro"): ("Qualcomm", "Adreno (TM) 650"),
    ("Xiaomi", "Xiaomi Pad 6"): ("Qualcomm", "Adreno (TM) 650"),
    ("Xiaomi", "Xiaomi Pad 7"): ("Qualcomm", "Adreno (TM) 732"),
    ("Xiaomi", "Xiaomi Pad 7 Pro"): ("Qualcomm", "Adreno (TM) 735"),
    # ---- ZTE -------------------------------------------------------
    ("ZTE", "ZTE A2022L"): ("ARM", "Mali-G57 MP1"),
    ("ZTE", "ZTE A2022PG"): ("ARM", "Mali-G57 MP1"),
    ("ZTE", "ZTE A2023"): ("ARM", "Mali-G57 MP1"),
    ("ZTE", "ZTE A2023G"): ("ARM", "Mali-G57 MP1"),
    ("ZTE", "ZTE A2023P"): ("ARM", "Mali-G57 MP1"),
    ("ZTE", "ZTE A2023PG"): ("ARM", "Mali-G57 MP1"),
    # ---- iQOO ------------------------------------------------------
    ("iQOO", "iQOO 15"): ("Qualcomm", "Adreno (TM) 840"),
    ("iQOO", "iQOO 15 Ultra"): ("Qualcomm", "Adreno (TM) 840"),
    ("iQOO", "iQOO 15R"): ("Qualcomm", "Adreno (TM) 825"),
    ("iQOO", "iQOO 15T"): ("Qualcomm", "Adreno (TM) 830"),
    ("iQOO", "iQOO Neo 10"): ("Qualcomm", "Adreno (TM) 825"),
    ("iQOO", "iQOO Neo11"): ("Qualcomm", "Adreno (TM) 825"),
    ("iQOO", "iQOO Z10 Lite"): ("ARM", "Mali-G57 MC2"),
    ("iQOO", "iQOO Z10 Lite 5G"): ("ARM", "Mali-G57 MC2"),
    ("iQOO", "iQOO Z10 Turbo"): ("ARM", "Mali-G720-Immortalis MC7"),
    ("iQOO", "iQOO Z10R 5G"): ("ARM", "Mali-G57 MC2"),
    ("iQOO", "iQOO Z11"): ("ARM", "Mali-G615 MC2"),
    ("iQOO", "iQOO Z11 5G"): ("ARM", "Mali-G615 MC2"),
    ("iQOO", "iQOO Z11 Turbo"): ("ARM", "Mali-G720-Immortalis MC7"),
    ("iQOO", "iQOO Z11i"): ("ARM", "Mali-G615 MC2"),
    ("iQOO", "iQOO Z11x"): ("ARM", "Mali-G615 MC2"),
    ("iQOO", "iQOO Z11x 5G"): ("ARM", "Mali-G615 MC2"),
    ("iQOO", "iQOO Z9 Lite"): ("ARM", "Mali-G57 MC2"),
    # ---- itel ------------------------------------------------------
    ("itel", "itel  A24 2023"): ("Imagination Technologies", "PowerVR Rogue GE8322"),
    ("itel", "itel A24 2023"): ("Imagination Technologies", "PowerVR Rogue GE8322"),
    # ---- realme ----------------------------------------------------
    ("realme", "NARZO 70 5G"): ("ARM", "Mali-G57 MC2"),
    ("realme", "NARZO 70 Pro 5G"): ("ARM", "Mali-G68 MC4"),
    ("realme", "realme 10"): ("ARM", "Mali-G57 MC2"),
    ("realme", "realme 10 Pro"): ("Qualcomm", "Adreno (TM) 619"),
    ("realme", "realme 10 Pro+"): ("ARM", "Mali-G68 MC4"),
    ("realme", "realme 11"): ("ARM", "Mali-G57 MC2"),
    ("realme", "realme 11 Pro+"): ("ARM", "Mali-G68 MC4"),
    ("realme", "realme 12"): ("ARM", "Mali-G57 MC2"),
    ("realme", "realme GT 2"): ("Qualcomm", "Adreno (TM) 730"),
    ("realme", "realme GT 2 Pro"): ("Qualcomm", "Adreno (TM) 730"),
    ("realme", "realme GT 6"): ("Qualcomm", "Adreno (TM) 735"),
    ("realme", "realme GT 6T"): ("Qualcomm", "Adreno (TM) 732"),
    ("realme", "realme GT 7 Pro"): ("Qualcomm", "Adreno (TM) 830"),
    ("realme", "realme GT 7T"): ("ARM", "Mali-G720-Immortalis MC7"),
    ("realme", "realme GT5"): ("Qualcomm", "Adreno (TM) 740"),
    ("realme", "realme GT5 Pro"): ("Qualcomm", "Adreno (TM) 750"),
    ("realme", "realme NARZO 70 Turbo 5G"): ("ARM", "Mali-G615 MC2"),
    ("realme", "realme NARZO 80 Pro 5G"): ("ARM", "Mali-G615 MC2"),
    # ---- vivo ------------------------------------------------------
    ("vivo", "V2022"): ("Qualcomm", "Adreno (TM) 610"),
    ("vivo", "V2023"): ("ARM", "Mali-G57 MC2"),
    ("vivo", "V2023A"): ("ARM", "Mali-G57 MC2"),
    ("vivo", "V2023EA"): ("ARM", "Mali-G57 MC2"),
    ("vivo", "V2024"): ("Qualcomm", "Adreno (TM) 619"),
    ("vivo", "V2024A"): ("Qualcomm", "Adreno (TM) 619"),
    ("vivo", "V2025"): ("ARM", "Mali-G57 MC2"),
    ("vivo", "V2025A"): ("ARM", "Mali-G57 MC2"),
    ("vivo", "vivo S15 Pro"): ("ARM", "Mali-G610 MC6"),
    ("vivo", "vivo S15e"): ("ARM", "Mali-G78 MP10"),
    ("vivo", "vivo S16"): ("Qualcomm", "Adreno (TM) 720"),
    ("vivo", "vivo S16 Pro"): ("ARM", "Mali-G610 MC6"),
    ("vivo", "vivo S16e"): ("ARM", "Mali-G78 MP10"),
    ("vivo", "vivo S17e"): ("ARM", "Mali-G610 MC4"),
    ("vivo", "vivo S18"): ("Qualcomm", "Adreno (TM) 720"),
    ("vivo", "vivo S18e"): ("ARM", "Mali-G610 MC4"),
    ("vivo", "vivo S19"): ("Qualcomm", "Adreno (TM) 720"),
    ("vivo", "vivo T2"): ("Qualcomm", "Adreno (TM) 710"),
    ("vivo", "vivo X200"): ("ARM", "Mali-G925-Immortalis MC12"),
    ("vivo", "vivo X80"): ("ARM", "Mali-G710 MC10"),
    ("vivo", "vivo X80 Pro"): ("Qualcomm", "Adreno (TM) 730"),
    ("vivo", "vivo X90"): ("ARM", "Mali-G715-Immortalis MC11"),
    ("vivo", "vivo X90 Pro"): ("ARM", "Mali-G715-Immortalis MC11"),
    ("vivo", "vivo X90 Pro+"): ("Qualcomm", "Adreno (TM) 740"),
}

# Marketing names sold on more than one SoC, keyed by model code.  Only
# those belong here: a code is the identity a site keys on, so a code in
# this table must not also sit in the name table above.
GPU_BY_CODE = {
    "SC-51C": ("Qualcomm", "Adreno (TM) 730"),
    "SC-51E": ("Qualcomm", "Adreno (TM) 750"),
    "SC-52C": ("Qualcomm", "Adreno (TM) 730"),
    "SCG13": ("Qualcomm", "Adreno (TM) 730"),
    "SCG14": ("Qualcomm", "Adreno (TM) 730"),
    "SCG24": ("Samsung", "Xclipse 920"),
    "SCG25": ("Qualcomm", "Adreno (TM) 750"),
    "SM-S7110": ("Samsung", "Xclipse 920"),
    "SM-S711B": ("Samsung", "Xclipse 920"),
    "SM-S711N": ("Samsung", "Xclipse 920"),
    "SM-S711U": ("Qualcomm", "Adreno (TM) 730"),
    "SM-S711U1": ("Qualcomm", "Adreno (TM) 730"),
    "SM-S711W": ("Qualcomm", "Adreno (TM) 730"),
    "SM-S9010": ("Qualcomm", "Adreno (TM) 730"),
    "SM-S901B": ("Samsung", "Xclipse 920"),
    "SM-S901E": ("Qualcomm", "Adreno (TM) 730"),
    "SM-S901N": ("Qualcomm", "Adreno (TM) 730"),
    "SM-S901U": ("Qualcomm", "Adreno (TM) 730"),
    "SM-S901U1": ("Qualcomm", "Adreno (TM) 730"),
    "SM-S901W": ("Qualcomm", "Adreno (TM) 730"),
    "SM-S9060": ("Qualcomm", "Adreno (TM) 730"),
    "SM-S906B": ("Samsung", "Xclipse 920"),
    "SM-S906E": ("Qualcomm", "Adreno (TM) 730"),
    "SM-S906N": ("Qualcomm", "Adreno (TM) 730"),
    "SM-S906U": ("Qualcomm", "Adreno (TM) 730"),
    "SM-S906U1": ("Qualcomm", "Adreno (TM) 730"),
    "SM-S906W": ("Qualcomm", "Adreno (TM) 730"),
    "SM-S9080": ("Qualcomm", "Adreno (TM) 730"),
    "SM-S908B": ("Samsung", "Xclipse 920"),
    "SM-S908E": ("Qualcomm", "Adreno (TM) 730"),
    "SM-S908N": ("Qualcomm", "Adreno (TM) 730"),
    "SM-S908U": ("Qualcomm", "Adreno (TM) 730"),
    "SM-S908U1": ("Qualcomm", "Adreno (TM) 730"),
    "SM-S908W": ("Qualcomm", "Adreno (TM) 730"),
    "SM-S9210": ("Qualcomm", "Adreno (TM) 750"),
    "SM-S921B": ("Samsung", "Xclipse 940"),
    "SM-S921E": ("Samsung", "Xclipse 940"),
    "SM-S921N": ("Samsung", "Xclipse 940"),
    "SM-S921Q": ("Qualcomm", "Adreno (TM) 750"),
    "SM-S921U": ("Qualcomm", "Adreno (TM) 750"),
    "SM-S921U1": ("Qualcomm", "Adreno (TM) 750"),
    "SM-S921W": ("Qualcomm", "Adreno (TM) 750"),
    "SM-S9260": ("Qualcomm", "Adreno (TM) 750"),
    "SM-S926B": ("Samsung", "Xclipse 940"),
    "SM-S926N": ("Samsung", "Xclipse 940"),
    "SM-S926U": ("Qualcomm", "Adreno (TM) 750"),
    "SM-S926U1": ("Qualcomm", "Adreno (TM) 750"),
    "SM-S926W": ("Qualcomm", "Adreno (TM) 750"),
}

# Backstop for a model neither table above covers -- a handset the CSV
# gained after this table was written.  It is a fallback, not the rule:
# falling back to the brand restores exactly the one-GPU-per-brand
# degeneracy the per-model tables exist to avoid, so a model that lands
# here wants an entry of its own.
GPU_BY_BRAND = {
    "Samsung": ("Qualcomm", "Adreno (TM) 740"),
    "Google": ("Qualcomm", "Adreno (TM) 740"),
    "Xiaomi": ("Qualcomm", "Adreno (TM) 730"),
    "Redmi": ("Qualcomm", "Adreno (TM) 619"),
    "POCO": ("Qualcomm", "Adreno (TM) 642L"),
    "iQOO": ("Qualcomm", "Adreno (TM) 730"),
    "OnePlus": ("Qualcomm", "Adreno (TM) 740"),
    "OPPO": ("Qualcomm", "Adreno (TM) 730"),
    "vivo": ("Qualcomm", "Adreno (TM) 730"),
    "realme": ("Qualcomm", "Adreno (TM) 730"),
    "Motorola": ("Qualcomm", "Adreno (TM) 642L"),
    "HONOR": ("Qualcomm", "Adreno (TM) 730"),
    "Sony": ("Qualcomm", "Adreno (TM) 740"),
    "ASUS": ("Qualcomm", "Adreno (TM) 740"),
    "Nothing": ("Qualcomm", "Adreno (TM) 730"),
    "Infinix": ("ARM", "Mali-G57 MC2"),
    "TECNO": ("ARM", "Mali-G57 MC2"),
    "itel": ("ARM", "Mali-G57 MC2"),
    "Nokia": ("Qualcomm", "Adreno (TM) 619"),
    "ZTE": ("Qualcomm", "Adreno (TM) 642L"),
    "TCL": ("Qualcomm", "Adreno (TM) 610"),
}
DEFAULT_GPU = ("Qualcomm", "Adreno (TM) 619")


def gpu_for(brand, name, model):
    """The (vendor, renderer) pair the catalogue emits for one device."""
    gpu = GPU_BY_CODE.get(model)
    if gpu is None:
        gpu = GPU_BY_MODEL.get((brand, name))
    if gpu is None:
        gpu = GPU_BY_BRAND.get(brand, DEFAULT_GPU)
    return gpu


def slug(text):
    """A stable, human-readable id: 'samsung-sm-s918b'."""
    s = re.sub(r"[^A-Za-z0-9]+", "-", text).strip("-").lower()
    return re.sub(r"-{2,}", "-", s)


def load_rows(path):
    raw = open(path, "rb").read()
    for encoding in ("utf-16", "utf-8-sig", "utf-8"):
        try:
            text = raw.decode(encoding)
            break
        except UnicodeDecodeError:
            continue
    else:
        raise SystemExit("could not decode %s" % path)
    return [r for r in csv.DictReader(io.StringIO(text)) if r.get("Model")]


# Vendors frequently print the launch year into the name itself ("moto g 5G
# (2023)", "TECNO SPARK Go 2024", "itel A24 2023").  That is the vendor's own
# statement rather than an inference, so it is consulted after the explicit
# rules -- a rule that knows the generation is more precise and wins -- and
# only for a year the catalogue has an Android release for.
YEAR_IN_NAME = re.compile(r"(?<![0-9])(20[0-9]{2})(?![0-9])")


def classify(brand, name):
    """The launch year of the generation [name] belongs to, or None.

    The rules carry equal weight; the first one whose brand and name pattern
    match wins, and the year comes from the rule's generation table — never
    from a range, so two generations of one family cannot end up sharing a
    launch year.
    """
    for rule_brand, pattern, generations in RULES:
        if brand.lower() != rule_brand.lower():
            continue
        m = re.search(pattern, name)
        if not m:
            continue
        token = next((g for g in m.groups() if g), None)
        if token is None:
            continue
        # Spaces are dropped so "GT 2" and "GT2" are one generation.
        year = generations.get(token.replace(" ", ""))
        # A table may name a generation the catalogue has no Android release
        # for (a 2021 handset, say).  That is not a match, but it is not a
        # verdict either: a year the name states itself still stands.
        if year in ANDROID_BY_YEAR:
            return year
        break
    # No rule knew this family, so fall back on a year the name states.
    # Only for a maker this file curates: the list is full of tablets, TV
    # boxes and toy phones whose names carry a year, and a profile claiming
    # to be one of those is a profile no real phone ever presents.
    if brand not in CURATED:
        return None
    m = YEAR_IN_NAME.search(name)
    if m:
        stated = int(m.group(1))
        if stated in ANDROID_BY_YEAR:
            return stated
    return None


def build_user_agent(model, android, build_id, chrome, tablet=False):
    return (
        "Mozilla/5.0 (Linux; Android %s; %s Build/%s) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/%s%s Safari/537.36"
        % (android, model, build_id, chrome, "" if tablet else " Mobile")
    )


def main():
    parser = argparse.ArgumentParser(
        description="Regenerate the bundled device catalogue (Devices.kt) "
                    "and the joined TSV from Google's supported-devices CSV.")
    parser.add_argument("csv", help="path to supported_devices.csv (UTF-16 or UTF-8)")
    parser.add_argument("--out", default=None,
                        help="write the Kotlin catalogue here instead of the "
                             "committed Devices.kt")
    parser.add_argument("--tsv-out", default=None,
                        help="write the joined TSV here instead of "
                             "android/tools/devices.tsv")
    args = parser.parse_args()
    rows = load_rows(args.csv)

    # One entry per (brand, model code).  A model code is the identity a site
    # actually keys on, so the same code listed under two marketing names is
    # one device, not two.
    seen = {}
    for r in rows:
        brand = (r.get("Retail Branding") or "").strip()
        name = (r.get("Marketing Name") or "").strip()
        model = (r.get("Model") or "").strip()
        if not brand or not name or not model:
            continue
        if DENY.search(name) or DENY.search(brand):
            continue
        canon = CANONICAL_BRAND.get(brand.lower(), brand)
        year = classify(canon, name)
        if year is None:
            continue
        key = (canon, model)
        # Two marketing names can map to one code; keep the first, which the
        # rule order has already made the more specific of the two.
        seen.setdefault(key, (name, year))

    devices = []
    for (brand, model), (name, year) in sorted(seen.items()):
        android = ANDROID_BY_YEAR[year]
        chrome = CHROME_BY_YEAR[year]
        build_id = BUILD_IDS[android]
        gpu_vendor, gpu_renderer = gpu_for(brand, name, model)
        tablet = bool(TABLET.search(name))
        devices.append({
            "id": slug("%s-%s" % (brand, model)),
            "brand": brand,
            "model": name,
            "code": model,
            "year": year,
            "android": android,
            "chrome": chrome,
            "build": build_id,
            "ua": build_user_agent(model, android, build_id, chrome, tablet),
            "memory": device_memory(year),
            "cores": hardware_concurrency(year),
            "gpu_vendor": gpu_vendor,
            "gpu_renderer": gpu_renderer,
            "form": "tablet" if tablet else "phone",
        })

    if not devices:
        raise SystemExit("no devices matched — is the CSV the right one?")
    tsv_path = args.tsv_out or os.path.join(os.path.dirname(__file__), "devices.tsv")
    write_tsv(devices, tsv_path)
    write_kotlin(devices, args.out)
    print("devices: %d" % len(devices))
    years = {}
    for d in devices:
        years[d["year"]] = years.get(d["year"], 0) + 1
    print("by year: %s" % sorted(years.items()))
    brands = {}
    for d in devices:
        brands[d["brand"]] = brands.get(d["brand"], 0) + 1
    print("by brand: %s" % sorted(brands.items(), key=lambda kv: -kv[1]))


def write_tsv(devices, path):
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("id\tbrand\tmodel\tcode\tyear\tandroid\tchrome\tbuild\tmemory\tcores\tgpu_vendor\tgpu_renderer\tform\tua\n")
        for d in devices:
            fh.write("\t".join(str(d[k]) for k in (
                "id", "brand", "model", "code", "year", "android", "chrome",
                "build", "memory", "cores", "gpu_vendor", "gpu_renderer",
                "form", "ua",
            )) + "\n")


KOTLIN_OUT = os.path.join(
    os.path.dirname(__file__), "..", "core", "domain", "src", "main",
    "kotlin", "com", "roombrowser", "domain", "model", "Devices.kt",
)


def kotlin_str(text):
    return '"%s"' % text.replace("\\", "\\\\").replace('"', '\\"')


def write_kotlin(devices, path=None):
    out = io.StringIO()
    out.write(
        "// GENERATED FILE, DO NOT EDIT BY HAND.\n"
        "// Produced by android/tools/gen-devices.py from Google's public\n"
        "// supported-devices list; the joined data is in android/tools/devices.tsv.\n"
        "// Every model code in here belongs to a real handset or tablet\n"
        "// that Google Play has shipped to.\n"
        "\n"
        "package com.roombrowser.domain.model\n"
        "\n"
        "/**\n"
        " * One Android device the browser can present itself as.\n"
        " *\n"
        " * [ua] is the User-Agent that handset's Chrome actually sends. The\n"
        " * remaining fields are the non-geometric fingerprint surface: they\n"
        " * describe the device, and NONE of them is a screen measurement. Screen\n"
        " * size and devicePixelRatio are deliberately absent, because this\n"
        " * record does not know them: what a page is told the screen is belongs\n"
        " * to the profile, not the handset, and \"Screen size\" in that profile's\n"
        " * settings decides it — real by default, and stated where the cost of\n"
        " * claiming a different one is visible. A device here therefore never\n"
        " * carries a screen nobody chose. See SECURITY.md.\n"
        " */\n"
        "data class Device(\n"
        "    val id: String,\n"
        "    val brand: String,\n"
        "    val model: String,\n"
        "    val code: String,\n"
        "    val year: Int,\n"
        "    val androidVersion: String,\n"
        "    val chromeVersion: String,\n"
        "    val buildId: String,\n"
        "    val userAgent: String,\n"
        "    val deviceMemoryGb: Int,\n"
        "    val hardwareConcurrency: Int,\n"
        "    val gpuVendor: String,\n"
        "    val gpuRenderer: String,\n"
        "    /** \"phone\" or \"tablet\"; a tablet's UA carries no \"Mobile\". */\n"
        "    val formFactor: String,\n"
        ")\n"
        "\n"
        "object Devices {\n"
        "\n"
        "    val all: List<Device> = listOf(\n"
    )
    for d in devices:
        out.write(
            "        Device(\n"
            "            id = %s, brand = %s, model = %s, code = %s,\n"
            "            year = %d, androidVersion = %s, chromeVersion = %s, buildId = %s,\n"
            "            userAgent = %s,\n"
            "            deviceMemoryGb = %d, hardwareConcurrency = %d,\n"
            "            gpuVendor = %s, gpuRenderer = %s, formFactor = %s\n"
            "        ),\n" % (
                kotlin_str(d["id"]), kotlin_str(d["brand"]), kotlin_str(d["model"]),
                kotlin_str(d["code"]), d["year"], kotlin_str(d["android"]),
                kotlin_str(d["chrome"]), kotlin_str(d["build"]), kotlin_str(d["ua"]),
                d["memory"], d["cores"], kotlin_str(d["gpu_vendor"]),
                kotlin_str(d["gpu_renderer"]), kotlin_str(d["form"]),
            )
        )
    out.write(
        "    )\n"
        "\n"
        "    val byId: Map<String, Device> = all.associateBy { it.id }\n"
        "\n"
        "    fun find(id: String?): Device? = id?.let { byId[it] }\n"
        "\n"
        "    /**\n"
        "     * A device no other profile is using. [taken] is the set of ids\n"
        "     * already assigned to the registry; every profile gets a different\n"
        "     * handset until the catalogue runs out, at which point the pool is\n"
        "     * the whole catalogue again (one shared device beats no assignment).\n"
        "     */\n"
        "    fun random(taken: Set<String> = emptySet()): Device {\n"
        "        val free = all.filterNot { it.id in taken }\n"
        "        return (if (free.isEmpty()) all else free).random()\n"
        "    }\n"
        "}\n"
    )
    path = os.path.normpath(path or KOTLIN_OUT)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(out.getvalue())


if __name__ == "__main__":
    main()
