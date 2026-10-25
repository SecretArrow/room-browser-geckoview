// GENERATED FILE, DO NOT EDIT BY HAND.
// Produced by android/tools/gen-devices.py from Google's public
// supported-devices list; the joined data is in android/tools/devices.tsv.
// Every model code in here belongs to a real handset or tablet
// that Google Play has shipped to.

package com.roombrowser.domain.model

/**
 * One Android device the browser can present itself as.
 *
 * [ua] is the User-Agent that handset's Chrome actually sends. The
 * remaining fields are the non-geometric fingerprint surface: they
 * describe the device, and NONE of them is a screen measurement. Screen
 * size and devicePixelRatio are deliberately absent, because this
 * record does not know them: what a page is told the screen is belongs
 * to the profile, not the handset, and "Screen size" in that profile's
 * settings decides it — real by default, and stated where the cost of
 * claiming a different one is visible. A device here therefore never
 * carries a screen nobody chose. See SECURITY.md.
 */
data class Device(
    val id: String,
    val brand: String,
    val model: String,
    val code: String,
    val year: Int,
    val androidVersion: String,
    val chromeVersion: String,
    val buildId: String,
    val userAgent: String,
    val deviceMemoryGb: Int,
    val hardwareConcurrency: Int,
    val gpuVendor: String,
    val gpuRenderer: String,
    /** "phone" or "tablet"; a tablet's UA carries no "Mobile". */
    val formFactor: String,
)

object Devices {

    val all: List<Device> = listOf(
        Device(
            id = "asus-asusai2501a", brand = "ASUS", model = "ROG Phone 9", code = "ASUSAI2501A",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; ASUSAI2501A Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "asus-asusai2501b", brand = "ASUS", model = "ROG Phone 9", code = "ASUSAI2501B",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; ASUSAI2501B Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "asus-asusai2501c", brand = "ASUS", model = "ROG Phone 9 Pro", code = "ASUSAI2501C",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; ASUSAI2501C Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "asus-asusai2501h", brand = "ASUS", model = "Zenfone 12 Ultra", code = "ASUSAI2501H",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; ASUSAI2501H Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "asus-asus-ai2202", brand = "ASUS", model = "Zenfone 9", code = "ASUS_AI2202",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; ASUS_AI2202 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "asus-asus-ai2302", brand = "ASUS", model = "Zenfone 10", code = "ASUS_AI2302",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; ASUS_AI2302 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "asus-asus-ai2401-h", brand = "ASUS", model = "Zenfone 11 Ultra", code = "ASUS_AI2401_H",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; ASUS_AI2401_H Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "asus-asus-i006d", brand = "ASUS", model = "Zenfone 8", code = "ASUS_I006D",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; ASUS_I006D Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 660", formFactor = "phone"
        ),
        Device(
            id = "asus-zs590ks", brand = "ASUS", model = "Zenfone 8", code = "ZS590KS",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; ZS590KS Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 660", formFactor = "phone"
        ),
        Device(
            id = "blu-g50-mega-2022", brand = "Blu", model = "G50 MEGA 2022", code = "G50 Mega 2022",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; G50 Mega 2022 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "blu-m8l-2022", brand = "Blu", model = "M8L 2022", code = "M8L 2022",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; M8L 2022 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "blu-studio-x10-2022", brand = "Blu", model = "Studio X10 2022", code = "Studio X10 2022",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Studio X10 2022 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "blu-studio-x10l-2022", brand = "Blu", model = "Studio X10L 2022", code = "Studio X10L 2022",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Studio X10L 2022 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "google-pixel-6a", brand = "Google", model = "Pixel 6a", code = "Pixel 6a",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Pixel 6a Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G78 MP20", formFactor = "phone"
        ),
        Device(
            id = "google-pixel-7", brand = "Google", model = "Pixel 7", code = "Pixel 7",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Pixel 7 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G710 MP7", formFactor = "phone"
        ),
        Device(
            id = "google-pixel-7a", brand = "Google", model = "Pixel 7a", code = "Pixel 7a",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 7a Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G710 MP7", formFactor = "phone"
        ),
        Device(
            id = "google-pixel-8", brand = "Google", model = "Pixel 8", code = "Pixel 8",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 8 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G715-Immortalis MC10", formFactor = "phone"
        ),
        Device(
            id = "google-pixel-8a", brand = "Google", model = "Pixel 8a", code = "Pixel 8a",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8a Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G715-Immortalis MC10", formFactor = "phone"
        ),
        Device(
            id = "google-pixel-9", brand = "Google", model = "Pixel 9", code = "Pixel 9",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 9 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G715-Immortalis MC10", formFactor = "phone"
        ),
        Device(
            id = "google-pixel-9a", brand = "Google", model = "Pixel 9a", code = "Pixel 9a",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; Pixel 9a Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G715-Immortalis MC10", formFactor = "phone"
        ),
        Device(
            id = "google-pixel-fold", brand = "Google", model = "Pixel Fold", code = "Pixel Fold",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; Pixel Fold Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G710 MP7", formFactor = "phone"
        ),
        Device(
            id = "google-pixel-tablet", brand = "Google", model = "Pixel Tablet", code = "Pixel Tablet",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; Pixel Tablet Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G710 MP7", formFactor = "tablet"
        ),
        Device(
            id = "honor-any-lx1", brand = "HONOR", model = "HONOR Magic4 Lite", code = "ANY-LX1",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; ANY-LX1 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "honor-any-lx2", brand = "HONOR", model = "HONOR X9", code = "ANY-LX2",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; ANY-LX2 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "honor-any-lx3", brand = "HONOR", model = "HONOR X9", code = "ANY-LX3",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; ANY-LX3 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "honor-brp-nx1m", brand = "HONOR", model = "HONOR Magic7 Lite", code = "BRP-NX1M",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; BRP-NX1M Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "honor-brp-nx3", brand = "HONOR", model = "HONOR Magic7 Lite", code = "BRP-NX3",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; BRP-NX3 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "honor-bvl-an00", brand = "HONOR", model = "HONOR Magic6", code = "BVL-AN00",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; BVL-AN00 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "honor-bvl-an16", brand = "HONOR", model = "HONOR Magic6 Pro", code = "BVL-AN16",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; BVL-AN16 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "honor-bvl-n49", brand = "HONOR", model = "HONOR Magic6 Pro", code = "BVL-N49",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; BVL-N49 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "honor-cma-lx1", brand = "HONOR", model = "HONOR X7", code = "CMA-LX1",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; CMA-LX1 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 610", formFactor = "phone"
        ),
        Device(
            id = "honor-cma-lx2", brand = "HONOR", model = "HONOR X7", code = "CMA-LX2",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; CMA-LX2 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 610", formFactor = "phone"
        ),
        Device(
            id = "honor-cma-lx3", brand = "HONOR", model = "HONOR X7", code = "CMA-LX3",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; CMA-LX3 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 610", formFactor = "phone"
        ),
        Device(
            id = "honor-crt-lx1", brand = "HONOR", model = "HONOR X8a", code = "CRT-LX1",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; CRT-LX1 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "honor-crt-lx2", brand = "HONOR", model = "HONOR X8a", code = "CRT-LX2",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; CRT-LX2 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "honor-crt-lx3", brand = "HONOR", model = "HONOR X8a", code = "CRT-LX3",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; CRT-LX3 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "honor-crt-nx1", brand = "HONOR", model = "HONOR 90 Lite", code = "CRT-NX1",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; CRT-NX1 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "honor-crt-nx3", brand = "HONOR", model = "HONOR 90 Lite", code = "CRT-NX3",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; CRT-NX3 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "honor-eli-an00", brand = "HONOR", model = "HONOR 200", code = "ELI-AN00",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; ELI-AN00 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 720", formFactor = "phone"
        ),
        Device(
            id = "honor-eli-nx9", brand = "HONOR", model = "HONOR 200", code = "ELI-NX9",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; ELI-NX9 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 720", formFactor = "phone"
        ),
        Device(
            id = "honor-elp-an00", brand = "HONOR", model = "HONOR 200 Pro", code = "ELP-AN00",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; ELP-AN00 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 735", formFactor = "phone"
        ),
        Device(
            id = "honor-elp-nx9", brand = "HONOR", model = "HONOR 200 Pro", code = "ELP-NX9",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; ELP-NX9 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 735", formFactor = "phone"
        ),
        Device(
            id = "honor-fne-nx9", brand = "HONOR", model = "HONOR 70", code = "FNE-NX9",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; FNE-NX9 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 642L", formFactor = "phone"
        ),
        Device(
            id = "honor-lge-nx9", brand = "HONOR", model = "HONOR Magic4 Pro", code = "LGE-NX9",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; LGE-NX9 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "honor-maa-an00", brand = "HONOR", model = "HONOR 100", code = "MAA-AN00",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; MAA-AN00 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 720", formFactor = "phone"
        ),
        Device(
            id = "honor-maa-an10", brand = "HONOR", model = "HONOR 100 Pro", code = "MAA-AN10",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; MAA-AN10 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "honor-pgt-n09", brand = "HONOR", model = "HONOR Magic5", code = "PGT-N09",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; PGT-N09 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "honor-pgt-n19", brand = "HONOR", model = "HONOR Magic5 Pro", code = "PGT-N19",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; PGT-N19 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "honor-ptp-n29", brand = "HONOR", model = "HONOR Magic7", code = "PTP-N29",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; PTP-N29 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "honor-ptp-n49", brand = "HONOR", model = "HONOR Magic7 Pro", code = "PTP-N49",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; PTP-N49 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "honor-rbn-nx1", brand = "HONOR", model = "HONOR 70 Lite", code = "RBN-NX1",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; RBN-NX1 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "honor-rea-nx9", brand = "HONOR", model = "HONOR 90", code = "REA-NX9",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; REA-NX9 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 644", formFactor = "phone"
        ),
        Device(
            id = "honor-rky-lx1", brand = "HONOR", model = "HONOR X7a", code = "RKY-LX1",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; RKY-LX1 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "honor-rky-lx2", brand = "HONOR", model = "HONOR X7a", code = "RKY-LX2",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; RKY-LX2 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "honor-rky-lx3", brand = "HONOR", model = "HONOR X7a", code = "RKY-LX3",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; RKY-LX3 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "honor-tfy-lx1", brand = "HONOR", model = "HONOR X8", code = "TFY-LX1",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; TFY-LX1 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 610", formFactor = "phone"
        ),
        Device(
            id = "honor-tfy-lx2", brand = "HONOR", model = "HONOR X8", code = "TFY-LX2",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; TFY-LX2 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 610", formFactor = "phone"
        ),
        Device(
            id = "honor-tfy-lx3", brand = "HONOR", model = "HONOR X8", code = "TFY-LX3",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; TFY-LX3 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 610", formFactor = "phone"
        ),
        Device(
            id = "honor-wdy-lx1", brand = "HONOR", model = "HONOR X6a", code = "WDY-LX1",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; WDY-LX1 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "honor-wdy-lx2", brand = "HONOR", model = "HONOR X6a", code = "WDY-LX2",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; WDY-LX2 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "honor-wdy-lx3", brand = "HONOR", model = "HONOR X6a", code = "WDY-LX3",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; WDY-LX3 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6528", brand = "Infinix", model = "Infinix HOT 40i", code = "Infinix X6528",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; Infinix X6528 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MP1", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6528b", brand = "Infinix", model = "Infinix HOT 40i", code = "Infinix X6528B",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; Infinix X6528B Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MP1", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x662", brand = "Infinix", model = "HOT 11", code = "Infinix X662",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X662 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x662b", brand = "Infinix", model = "HOT 11", code = "Infinix X662B",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X662B Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x663b", brand = "Infinix", model = "Infinix NOTE 11", code = "Infinix X663B",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X663B Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x663c", brand = "Infinix", model = "Infinix NOTE 12", code = "Infinix X663C",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X663C Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x665", brand = "Infinix", model = "HOT 12i", code = "Infinix X665",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X665 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x665b", brand = "Infinix", model = "HOT 12i", code = "Infinix X665B",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X665B Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x665c", brand = "Infinix", model = "HOT 20i", code = "Infinix X665C",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X665C Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x665e", brand = "Infinix", model = "HOT 20i", code = "Infinix X665E",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X665E Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x666", brand = "Infinix", model = "HOT 20 5G", code = "Infinix X666",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X666 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x668", brand = "Infinix", model = "Infinix HOT 12 PRO", code = "Infinix X668",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X668 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x668c", brand = "Infinix", model = "Infinix HOT 12 PRO", code = "Infinix X668C",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X668C Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x669", brand = "Infinix", model = "Infinix HOT 30i", code = "Infinix X669",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; Infinix X669 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MP1", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x669c", brand = "Infinix", model = "Infinix HOT 30i", code = "Infinix X669C",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; Infinix X669C Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MP1", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x669d", brand = "Infinix", model = "Infinix HOT 30i", code = "Infinix X669D",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; Infinix X669D Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MP1", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x670", brand = "Infinix", model = "NOTE 12", code = "Infinix X670",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X670 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x671", brand = "Infinix", model = "Infinix NOTE 12 5G", code = "Infinix X671",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X671 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6710", brand = "Infinix", model = "NOTE 30 VIP", code = "Infinix X6710",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; Infinix X6710 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G77 MC9", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6711", brand = "Infinix", model = "Infinix NOTE 30 5G", code = "Infinix X6711",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; Infinix X6711 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6716", brand = "Infinix", model = "NOTE 30i", code = "Infinix X6716",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; Infinix X6716 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6716b", brand = "Infinix", model = "NOTE 30", code = "Infinix X6716B",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; Infinix X6716B Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x671b", brand = "Infinix", model = "Infinix NOTE 12 Pro 5G", code = "Infinix X671B",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X671B Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x672", brand = "Infinix", model = "NOTE 12 VIP", code = "Infinix X672",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X672 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6731", brand = "Infinix", model = "ZERO 30 5G", code = "Infinix X6731",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; Infinix X6731 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G77 MC9", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6731b", brand = "Infinix", model = "Infinix ZERO 30", code = "Infinix X6731B",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; Infinix X6731B Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x675", brand = "Infinix", model = "HOT 11 2022", code = "Infinix X675",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X675 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x676b", brand = "Infinix", model = "Infinix NOTE 12 PRO", code = "Infinix X676B",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X676B Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x676c", brand = "Infinix", model = "Infinix NOTE 12 2023", code = "Infinix X676C",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X676C Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x677", brand = "Infinix", model = "NOTE 12i 2022", code = "Infinix X677",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X677 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x678b", brand = "Infinix", model = "NOTE 30 Pro", code = "Infinix X678B",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; Infinix X678B Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6812", brand = "Infinix", model = "HOT 11S", code = "Infinix X6812",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X6812 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6812b", brand = "Infinix", model = "Infinix HOT 11S NFC", code = "Infinix X6812B",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X6812B Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6815c", brand = "Infinix", model = "ZERO 5G 2023", code = "Infinix X6815C",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; Infinix X6815C Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6815d", brand = "Infinix", model = "ZERO 5G 2023", code = "Infinix X6815D",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; Infinix X6815D Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6816c", brand = "Infinix", model = "Infinix HOT 12 Play", code = "Infinix X6816C",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X6816C Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6816d", brand = "Infinix", model = "Infinix HOT 12 Play NFC", code = "Infinix X6816D",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X6816D Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6817", brand = "Infinix", model = "Infinix HOT 12", code = "Infinix X6817",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X6817 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6819", brand = "Infinix", model = "Infinix NOTE 12i", code = "Infinix X6819",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X6819 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6821", brand = "Infinix", model = "Infinix ZERO 20", code = "Infinix X6821",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X6821 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6825", brand = "Infinix", model = "HOT 20 PLAY", code = "Infinix X6825",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X6825 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6826", brand = "Infinix", model = "Infinix HOT 20", code = "Infinix X6826",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X6826 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6826b", brand = "Infinix", model = "Infinix HOT 20", code = "Infinix X6826B",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X6826B Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6826c", brand = "Infinix", model = "Infinix HOT 20", code = "Infinix X6826C",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X6826C Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6827", brand = "Infinix", model = "HOT 20S", code = "Infinix X6827",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X6827 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6832", brand = "Infinix", model = "HOT 30 5G", code = "Infinix X6832",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; Infinix X6832 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6833b", brand = "Infinix", model = "Infinix NOTE 30", code = "Infinix X6833B",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; Infinix X6833B Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6835", brand = "Infinix", model = "Infinix HOT 30 PLAY", code = "Infinix X6835",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; Infinix X6835 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MP1", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6835b", brand = "Infinix", model = "Infinix HOT 30 PLAY", code = "Infinix X6835B",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; Infinix X6835B Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MP1", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6836", brand = "Infinix", model = "Infinix HOT 40", code = "Infinix X6836",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; Infinix X6836 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6837", brand = "Infinix", model = "Infinix HOT 40 Pro", code = "Infinix X6837",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; Infinix X6837 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6838", brand = "Infinix", model = "Infinix NOTE 40X 5G", code = "Infinix X6838",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; Infinix X6838 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6850", brand = "Infinix", model = "Infinix NOTE 40 Pro", code = "Infinix X6850",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; Infinix X6850 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6850b", brand = "Infinix", model = "Infinix NOTE 40S", code = "Infinix X6850B",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; Infinix X6850B Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6851", brand = "Infinix", model = "Infinix NOTE 40 Pro 5G", code = "Infinix X6851",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; Infinix X6851 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6851b", brand = "Infinix", model = "NOTE 40 Pro+ 5G", code = "Infinix X6851B",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; Infinix X6851B Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G610 MC6", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6852", brand = "Infinix", model = "Infinix NOTE 40 5G", code = "Infinix X6852",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; Infinix X6852 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6853", brand = "Infinix", model = "Infinix NOTE 40", code = "Infinix X6853",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; Infinix X6853 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6860", brand = "Infinix", model = "Infinix ZERO 40", code = "Infinix X6860",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; Infinix X6860 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6861", brand = "Infinix", model = "Infinix ZERO 40 5G", code = "Infinix X6861",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; Infinix X6861 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G610 MC6", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6871", brand = "Infinix", model = "GT 20 Pro", code = "Infinix X6871",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X6871 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G610 MC6", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6873", brand = "Infinix", model = "GT 30 Pro", code = "Infinix X6873",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; Infinix X6873 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC6", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x6876", brand = "Infinix", model = "GT 30", code = "Infinix X6876",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; Infinix X6876 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x689f", brand = "Infinix", model = "HOT 11", code = "Infinix X689F",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X689F Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x693", brand = "Infinix", model = "Infinix NOTE 11i", code = "Infinix X693",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X693 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x697", brand = "Infinix", model = "NOTE 11 Pro", code = "Infinix X697",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X697 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "infinix-infinix-x698", brand = "Infinix", model = "NOTE 11S", code = "Infinix X698",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Infinix X698 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "lenovo-tb132fu", brand = "Lenovo", model = "小新Pad Pro 2022", code = "TB132FU",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; TB132FU Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 650", formFactor = "phone"
        ),
        Device(
            id = "lenovo-tb138fc", brand = "Lenovo", model = "小新Pad Pro 2022", code = "TB138FC",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; TB138FC Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 650", formFactor = "phone"
        ),
        Device(
            id = "lenovo-tb301fu", brand = "Lenovo", model = "Lenovo Tab M8 (4th Gen) 2024", code = "TB301FU",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; TB301FU Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "tablet"
        ),
        Device(
            id = "lenovo-tb301xu", brand = "Lenovo", model = "Lenovo Tab M8 (4th Gen) 2024", code = "TB301XU",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; TB301XU Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "tablet"
        ),
        Device(
            id = "lenovo-tb350fu", brand = "Lenovo", model = "小新Pad Plus 2023", code = "TB350FU",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; TB350FU Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 650", formFactor = "phone"
        ),
        Device(
            id = "motorola-xt2251-1", brand = "Motorola", model = "motorola razr 2022", code = "XT2251-1",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; XT2251-1 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "motorola-moto-g-2025", brand = "Motorola", model = "moto g - 2025", code = "moto g - 2025",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; moto g - 2025 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "motorola-moto-g-5g-2022", brand = "Motorola", model = "moto g 5G (2022)", code = "moto g 5G (2022)",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; moto g 5G (2022) Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "motorola-moto-g-5g-2023", brand = "Motorola", model = "moto g 5G - 2023", code = "moto g 5G - 2023",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; moto g 5G - 2023 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "motorola-moto-g-5g-2024", brand = "Motorola", model = "moto g 5G - 2024", code = "moto g 5G - 2024",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; moto g 5G - 2024 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "motorola-moto-g-play-2024", brand = "Motorola", model = "moto g - 2025", code = "moto g play - 2024",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; moto g play - 2024 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "motorola-moto-g-power-2022", brand = "Motorola", model = "moto g power (2022)", code = "moto g power (2022)",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; moto g power (2022) Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "motorola-moto-g-power-2025", brand = "Motorola", model = "moto g power - 2025", code = "moto g power - 2025",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; moto g power - 2025 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "motorola-moto-g-power-5g-2023", brand = "Motorola", model = "moto g power 5G - 2023", code = "moto g power 5G - 2023",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; moto g power 5G - 2023 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "motorola-moto-g-power-5g-2024", brand = "Motorola", model = "moto g power - 2025", code = "moto g power 5G - 2024",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; moto g power 5G - 2024 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "motorola-moto-g-pure", brand = "Motorola", model = "moto g 5G (2022)", code = "moto g pure",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; moto g pure Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "motorola-moto-g-stylus-2022", brand = "Motorola", model = "moto g stylus (2022)", code = "moto g stylus (2022)",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; moto g stylus (2022) Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "motorola-moto-g-stylus-2023", brand = "Motorola", model = "moto g stylus (2023)", code = "moto g stylus (2023)",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; moto g stylus (2023) Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "motorola-moto-g-stylus-2025", brand = "Motorola", model = "moto g stylus 5G - 2025", code = "moto g stylus - 2025",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; moto g stylus - 2025 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "motorola-moto-g-stylus-5g-2022", brand = "Motorola", model = "moto g 5G - 2023", code = "moto g stylus 5G (2022)",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; moto g stylus 5G (2022) Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "motorola-moto-g-stylus-5g-2023", brand = "Motorola", model = "moto g stylus 5G - 2023", code = "moto g stylus 5G - 2023",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; moto g stylus 5G - 2023 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "motorola-moto-g-stylus-5g-2024", brand = "Motorola", model = "moto g stylus 5G - 2024", code = "moto g stylus 5G - 2024",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; moto g stylus 5G - 2024 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "motorola-moto-g-stylus-5g", brand = "Motorola", model = "moto g stylus 5G - 2023", code = "moto g stylus 5g",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; moto g stylus 5g Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "motorola-moto-g-50", brand = "Motorola", model = "moto g stylus 5G (2022)", code = "moto g(50)",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; moto g(50) Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "motorola-moto-g-50-5g", brand = "Motorola", model = "moto g power 5G - 2023", code = "moto g(50) 5G",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; moto g(50) 5G Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "motorola-motorola", brand = "Motorola", model = "moto g stylus (2022)", code = "motorola",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; motorola Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "motorola-motorola-edge-2022", brand = "Motorola", model = "motorola edge (2022)", code = "motorola edge (2022)",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; motorola edge (2022) Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G77 MC9", formFactor = "phone"
        ),
        Device(
            id = "motorola-motorola-edge-2023", brand = "Motorola", model = "motorola edge 2023", code = "motorola edge 2023",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; motorola edge 2023 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "motorola-motorola-edge-2024", brand = "Motorola", model = "motorola edge 2024", code = "motorola edge 2024",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; motorola edge 2024 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "motorola-motorola-edge-2025", brand = "Motorola", model = "motorola edge 2025", code = "motorola edge 2025",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; motorola edge 2025 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "motorola-motorola-edge-30-pro", brand = "Motorola", model = "motorola razr 2022", code = "motorola edge 30 pro",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; motorola edge 30 pro Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "motorola-motorola-edge-40-pro", brand = "Motorola", model = "moto g play - 2024", code = "motorola edge 40 pro",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; motorola edge 40 pro Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 610", formFactor = "phone"
        ),
        Device(
            id = "motorola-motorola-edge-50-fusion", brand = "Motorola", model = "moto g stylus 5G - 2025", code = "motorola edge 50 fusion",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; motorola edge 50 fusion Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "motorola-motorola-edge-50-neo", brand = "Motorola", model = "motorola edge 2025", code = "motorola edge 50 neo",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; motorola edge 50 neo Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "motorola-motorola-edge-plus-2022", brand = "Motorola", model = "motorola edge plus (2022)", code = "motorola edge plus (2022)",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; motorola edge plus (2022) Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "motorola-motorola-edge-plus-2023", brand = "Motorola", model = "motorola edge plus 2023", code = "motorola edge plus 2023",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; motorola edge plus 2023 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "motorola-motorola-edge-plus-5g-uw-2022", brand = "Motorola", model = "motorola edge plus 5G UW (2022)", code = "motorola edge plus 5G UW (2022)",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; motorola edge plus 5G UW (2022) Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "motorola-motorola-razr-2022", brand = "Motorola", model = "motorola razr 2022", code = "motorola razr 2022",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; motorola razr 2022 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "motorola-motorola-razr-2023", brand = "Motorola", model = "motorola razr 2023", code = "motorola razr 2023",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; motorola razr 2023 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 644", formFactor = "phone"
        ),
        Device(
            id = "motorola-motorola-razr-2024", brand = "Motorola", model = "motorola razr 2024", code = "motorola razr 2024",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; motorola razr 2024 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "motorola-motorola-razr-2025", brand = "Motorola", model = "motorola razr 2025", code = "motorola razr 2025",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; motorola razr 2025 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "motorola-motorola-razr-50-ultra", brand = "Motorola", model = "motorola razr ultra 2025", code = "motorola razr 50 ultra",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; motorola razr 50 ultra Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "motorola-motorola-razr-plus-2023", brand = "Motorola", model = "motorola razr plus 2023", code = "motorola razr plus 2023",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; motorola razr plus 2023 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "motorola-motorola-razr-plus-2024", brand = "Motorola", model = "motorola razr plus 2024", code = "motorola razr plus 2024",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; motorola razr plus 2024 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 735", formFactor = "phone"
        ),
        Device(
            id = "motorola-motorola-razr-plus-2025", brand = "Motorola", model = "motorola razr plus 2025", code = "motorola razr plus 2025",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; motorola razr plus 2025 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 735", formFactor = "phone"
        ),
        Device(
            id = "motorola-motorola-razr-ultra-2025", brand = "Motorola", model = "motorola razr ultra 2025", code = "motorola razr ultra 2025",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; motorola razr ultra 2025 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "nokia-nokia-c31", brand = "Nokia", model = "Nokia C31", code = "Nokia C31",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Nokia C31 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8322", formFactor = "phone"
        ),
        Device(
            id = "nokia-nokia-c32", brand = "Nokia", model = "Nokia C32", code = "Nokia C32",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; Nokia C32 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8322", formFactor = "phone"
        ),
        Device(
            id = "nokia-nokia-g11", brand = "Nokia", model = "Nokia G11", code = "Nokia G11",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Nokia G11 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MP1", formFactor = "phone"
        ),
        Device(
            id = "nokia-nokia-g21", brand = "Nokia", model = "Nokia G21", code = "Nokia G21",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Nokia G21 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MP1", formFactor = "phone"
        ),
        Device(
            id = "nokia-nokia-x20", brand = "Nokia", model = "Nokia X20", code = "Nokia X20",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Nokia X20 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "nothing-a024", brand = "Nothing", model = "Nothing Phone (3)", code = "A024",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; A024 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 825", formFactor = "phone"
        ),
        Device(
            id = "nothing-a059", brand = "Nothing", model = "Nothing Phone (3a)", code = "A059",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; A059 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 810", formFactor = "phone"
        ),
        Device(
            id = "nothing-a063", brand = "Nothing", model = "Nothing Phone (1)", code = "A063",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; A063 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 642L", formFactor = "phone"
        ),
        Device(
            id = "nothing-a065", brand = "Nothing", model = "Nothing Phone (2)", code = "A065",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; A065 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "nothing-a142", brand = "Nothing", model = "Nothing Phone (2a)", code = "A142",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; A142 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G610 MC4", formFactor = "phone"
        ),
        Device(
            id = "nothing-ain065", brand = "Nothing", model = "Nothing Phone (2)", code = "AIN065",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; AIN065 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "oppo-a302op", brand = "OPPO", model = "Reno10 Pro 5G", code = "A302OP",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; A302OP Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G610 MC6", formFactor = "phone"
        ),
        Device(
            id = "oppo-a303op", brand = "OPPO", model = "A79 5G", code = "A303OP",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; A303OP Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "oppo-a401op", brand = "OPPO", model = "Reno11 A", code = "A401OP",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; A401OP Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "oppo-a501op", brand = "OPPO", model = "Reno13 A", code = "A501OP",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; A501OP Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "oppo-cph2339", brand = "OPPO", model = "A77 5G", code = "CPH2339",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; CPH2339 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "oppo-cph2387", brand = "OPPO", model = "A57", code = "CPH2387",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; CPH2387 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "oppo-cph2407", brand = "OPPO", model = "A57", code = "CPH2407",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; CPH2407 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "oppo-cph2483", brand = "OPPO", model = "A78 5G", code = "CPH2483",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; CPH2483 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "oppo-cph2495", brand = "OPPO", model = "A78 5G", code = "CPH2495",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; CPH2495 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "oppo-cph2521", brand = "OPPO", model = "Reno10 Pro+ 5G", code = "CPH2521",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; CPH2521 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "oppo-cph2525", brand = "OPPO", model = "Reno10 Pro 5G", code = "CPH2525",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; CPH2525 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G610 MC6", formFactor = "phone"
        ),
        Device(
            id = "oppo-cph2529", brand = "OPPO", model = "A98 5G", code = "CPH2529",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; CPH2529 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "oppo-cph2531", brand = "OPPO", model = "Reno10 5G", code = "CPH2531",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; CPH2531 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "oppo-cph2541", brand = "OPPO", model = "Reno10 Pro 5G", code = "CPH2541",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; CPH2541 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G610 MC6", formFactor = "phone"
        ),
        Device(
            id = "oppo-cph2553", brand = "OPPO", model = "A79 5G", code = "CPH2553",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; CPH2553 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "oppo-cph2557", brand = "OPPO", model = "A79 5G", code = "CPH2557",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; CPH2557 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "oppo-cph2565", brand = "OPPO", model = "A78", code = "CPH2565",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; CPH2565 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "oppo-cph2577", brand = "OPPO", model = "A58", code = "CPH2577",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; CPH2577 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "oppo-cph2603", brand = "OPPO", model = "Reno11 F 5G", code = "CPH2603",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; CPH2603 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "oppo-cph2617", brand = "OPPO", model = "A59 5G", code = "CPH2617",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; CPH2617 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "oppo-cph2625", brand = "OPPO", model = "Reno12 5G", code = "CPH2625",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; CPH2625 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "oppo-cph2629", brand = "OPPO", model = "Reno12 Pro 5G", code = "CPH2629",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; CPH2629 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "oppo-cph2637", brand = "OPPO", model = "Reno12 F 5G", code = "CPH2637",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; CPH2637 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "oppo-cph2687", brand = "OPPO", model = "Reno12 F", code = "CPH2687",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; CPH2687 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "oppo-cph2689", brand = "OPPO", model = "Reno13 5G", code = "CPH2689",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; CPH2689 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC6", formFactor = "phone"
        ),
        Device(
            id = "oppo-cph2697", brand = "OPPO", model = "Reno13 Pro 5G", code = "CPH2697",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; CPH2697 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC6", formFactor = "phone"
        ),
        Device(
            id = "oppo-cph2699", brand = "OPPO", model = "Reno13 F 5G", code = "CPH2699",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; CPH2699 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "oppo-cph2701", brand = "OPPO", model = "Reno13 F", code = "CPH2701",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; CPH2701 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "oppo-opg05", brand = "OPPO", model = "Reno13 A", code = "OPG05",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; OPG05 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "oppo-oppo-a57", brand = "OPPO", model = "A57", code = "OPPO A57",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; OPPO A57 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "oppo-oppo-a57t", brand = "OPPO", model = "A57", code = "OPPO A57t",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; OPPO A57t Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "oppo-oppo-a59", brand = "OPPO", model = "A59", code = "OPPO A59",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; OPPO A59 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "oppo-oppo-a59m", brand = "OPPO", model = "A59", code = "OPPO A59m",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; OPPO A59m Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "oppo-oppo-a59s", brand = "OPPO", model = "A59", code = "OPPO A59s",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; OPPO A59s Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "oppo-oppo-a77", brand = "OPPO", model = "A77", code = "OPPO A77",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; OPPO A77 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "oppo-oppo-a77t", brand = "OPPO", model = "A77", code = "OPPO A77t",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; OPPO A77t Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "oppo-oppo-a79", brand = "OPPO", model = "A79", code = "OPPO A79",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; OPPO A79 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "oppo-phu110", brand = "OPPO", model = "OPPO Reno10 Pro+ 5G", code = "PHU110",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; PHU110 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "oppo-phv110", brand = "OPPO", model = "OPPO Reno10 Pro 5G", code = "PHV110",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; PHV110 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G610 MC6", formFactor = "phone"
        ),
        Device(
            id = "oppo-phw110", brand = "OPPO", model = "OPPO Reno10 5G", code = "PHW110",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; PHW110 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "oppo-pjh110", brand = "OPPO", model = "Reno11", code = "PJH110",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; PJH110 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "oppo-pjj110", brand = "OPPO", model = "Reno11 Pro", code = "PJJ110",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; PJJ110 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G610 MC6", formFactor = "phone"
        ),
        Device(
            id = "oppo-pjv110", brand = "OPPO", model = "Reno12", code = "PJV110",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; PJV110 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "oppo-pjw110", brand = "OPPO", model = "Reno12 Pro", code = "PJW110",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; PJW110 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "oppo-pkk110", brand = "OPPO", model = "Reno13 Pro", code = "PKK110",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; PKK110 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC6", formFactor = "phone"
        ),
        Device(
            id = "oppo-pkm110", brand = "OPPO", model = "Reno13", code = "PKM110",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; PKM110 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC6", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2399", brand = "OnePlus", model = "OnePlus Nord 2T 5G", code = "CPH2399",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; CPH2399 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G77 MC9", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2401", brand = "OnePlus", model = "OnePlus Nord 2T 5G", code = "CPH2401",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; CPH2401 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G77 MC9", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2411", brand = "OnePlus", model = "OnePlus 10R 5G", code = "CPH2411",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; CPH2411 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G610 MC6", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2413", brand = "OnePlus", model = "OnePlus 10T 5G", code = "CPH2413",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; CPH2413 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2415", brand = "OnePlus", model = "OnePlus 10T 5G", code = "CPH2415",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; CPH2415 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2417", brand = "OnePlus", model = "OnePlus 10T 5G", code = "CPH2417",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; CPH2417 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2419", brand = "OnePlus", model = "OnePlus 10T 5G", code = "CPH2419",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; CPH2419 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2423", brand = "OnePlus", model = "OnePlus 10R 5G", code = "CPH2423",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; CPH2423 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G610 MC6", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2447", brand = "OnePlus", model = "OnePlus 11 5G", code = "CPH2447",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; CPH2447 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2449", brand = "OnePlus", model = "OnePlus 11 5G", code = "CPH2449",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; CPH2449 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2451", brand = "OnePlus", model = "OnePlus 11 5G", code = "CPH2451",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; CPH2451 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2459", brand = "OnePlus", model = "OnePlus Nord N20 5G", code = "CPH2459",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; CPH2459 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2487", brand = "OnePlus", model = "OnePlus 11R 5G", code = "CPH2487",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; CPH2487 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2491", brand = "OnePlus", model = "OnePlus Nord 3 5G", code = "CPH2491",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; CPH2491 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G710 MC10", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2493", brand = "OnePlus", model = "OnePlus Nord 3 5G", code = "CPH2493",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; CPH2493 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G710 MC10", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2513", brand = "OnePlus", model = "OnePlus Nord N30 5G", code = "CPH2513",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; CPH2513 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2515", brand = "OnePlus", model = "OnePlus Nord N30 5G", code = "CPH2515",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; CPH2515 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2573", brand = "OnePlus", model = "OnePlus 12", code = "CPH2573",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; CPH2573 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2581", brand = "OnePlus", model = "OnePlus 12", code = "CPH2581",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; CPH2581 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2583", brand = "OnePlus", model = "OnePlus 12", code = "CPH2583",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; CPH2583 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2585", brand = "OnePlus", model = "OnePlus 12R", code = "CPH2585",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; CPH2585 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2609", brand = "OnePlus", model = "OnePlus 12R", code = "CPH2609",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; CPH2609 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2611", brand = "OnePlus", model = "OnePlus 12R", code = "CPH2611",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; CPH2611 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2645", brand = "OnePlus", model = "OnePlus 13R", code = "CPH2645",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; CPH2645 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2647", brand = "OnePlus", model = "OnePlus 13R", code = "CPH2647",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; CPH2647 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2649", brand = "OnePlus", model = "OnePlus 13", code = "CPH2649",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; CPH2649 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2653", brand = "OnePlus", model = "OnePlus 13", code = "CPH2653",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; CPH2653 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2655", brand = "OnePlus", model = "OnePlus 13", code = "CPH2655",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; CPH2655 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2661", brand = "OnePlus", model = "OnePlus Nord 4", code = "CPH2661",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; CPH2661 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 732", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2663", brand = "OnePlus", model = "OnePlus Nord 4", code = "CPH2663",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; CPH2663 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 732", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2691", brand = "OnePlus", model = "OnePlus 13R", code = "CPH2691",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; CPH2691 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2707", brand = "OnePlus", model = "OnePlus Nord 5", code = "CPH2707",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; CPH2707 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 735", formFactor = "phone"
        ),
        Device(
            id = "oneplus-cph2709", brand = "OnePlus", model = "OnePlus Nord 5", code = "CPH2709",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; CPH2709 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 735", formFactor = "phone"
        ),
        Device(
            id = "oneplus-gn2200", brand = "OnePlus", model = "OnePlus Nord N20 5G", code = "GN2200",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; GN2200 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "oneplus-ne2210", brand = "OnePlus", model = "OnePlus 10 Pro", code = "NE2210",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; NE2210 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "oneplus-ne2211", brand = "OnePlus", model = "OnePlus 10 Pro 5G", code = "NE2211",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; NE2211 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "oneplus-ne2213", brand = "OnePlus", model = "OnePlus 10 Pro 5G", code = "NE2213",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; NE2213 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "oneplus-ne2215", brand = "OnePlus", model = "OnePlus 10 Pro 5G", code = "NE2215",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; NE2215 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "oneplus-ne2217", brand = "OnePlus", model = "OnePlus 10 Pro 5G", code = "NE2217",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; NE2217 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "oneplus-pjd110", brand = "OnePlus", model = "OnePlus 12", code = "PJD110",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; PJD110 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "oneplus-pjz110", brand = "OnePlus", model = "OnePlus 13", code = "PJZ110",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; PJZ110 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "oneplus-pkx110", brand = "OnePlus", model = "OnePlus 13T", code = "PKX110",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; PKX110 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "poco-21091116ag", brand = "POCO", model = "POCO M4 Pro 5G", code = "21091116AG",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 21091116AG Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "poco-21121210g", brand = "POCO", model = "POCO F4 GT", code = "21121210G",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 21121210G Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "poco-2201116pg", brand = "POCO", model = "POCO X4 Pro 5G", code = "2201116PG",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 2201116PG Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "poco-2201116pi", brand = "POCO", model = "POCO X4 Pro 5G", code = "2201116PI",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 2201116PI Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "poco-2201117pg", brand = "POCO", model = "POCO M4 Pro", code = "2201117PG",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 2201117PG Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "poco-2201117pi", brand = "POCO", model = "POCO M4 Pro", code = "2201117PI",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 2201117PI Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "poco-22021211rg", brand = "POCO", model = "POCO F4", code = "22021211RG",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 22021211RG Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 650", formFactor = "phone"
        ),
        Device(
            id = "poco-22021211ri", brand = "POCO", model = "POCO F4", code = "22021211RI",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 22021211RI Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 650", formFactor = "phone"
        ),
        Device(
            id = "poco-22041216g", brand = "POCO", model = "POCO X4 GT", code = "22041216G",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 22041216G Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G610 MC6", formFactor = "phone"
        ),
        Device(
            id = "poco-22041219pg", brand = "POCO", model = "POCO M4 5G", code = "22041219PG",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 22041219PG Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "poco-22041219pi", brand = "POCO", model = "POCO M4 5G", code = "22041219PI",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 22041219PI Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "poco-22071219cg", brand = "POCO", model = "POCO M5", code = "22071219CG",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; 22071219CG Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "poco-22071219ci", brand = "POCO", model = "POCO M5", code = "22071219CI",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; 22071219CI Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "poco-220733spi", brand = "POCO", model = "POCO C50", code = "220733SPI",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 220733SPI Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "poco-22101320g", brand = "POCO", model = "POCO X5 Pro 5G", code = "22101320G",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; 22101320G Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 642L", formFactor = "phone"
        ),
        Device(
            id = "poco-22101320i", brand = "POCO", model = "POCO X5 Pro 5G", code = "22101320I",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; 22101320I Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 642L", formFactor = "phone"
        ),
        Device(
            id = "poco-22127pc95i", brand = "POCO", model = "POCO C55", code = "22127PC95I",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; 22127PC95I Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "poco-2302epcc4h", brand = "POCO", model = "POCO C51", code = "2302EPCC4H",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 2302EPCC4H Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "poco-23049pcd8g", brand = "POCO", model = "POCO F5", code = "23049PCD8G",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; 23049PCD8G Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 725", formFactor = "phone"
        ),
        Device(
            id = "poco-23049pcd8i", brand = "POCO", model = "POCO F5", code = "23049PCD8I",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; 23049PCD8I Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 725", formFactor = "phone"
        ),
        Device(
            id = "poco-2305epcc4g", brand = "POCO", model = "POCO C51", code = "2305EPCC4G",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 2305EPCC4G Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "poco-23076pc4bi", brand = "POCO", model = "POCO M6 Pro 5G", code = "23076PC4BI",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 23076PC4BI Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "poco-2310fpca4g", brand = "POCO", model = "POCO C65", code = "2310FPCA4G",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; 2310FPCA4G Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "poco-2310fpca4i", brand = "POCO", model = "POCO C65", code = "2310FPCA4I",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; 2310FPCA4I Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "poco-23113rkc6g", brand = "POCO", model = "POCO F6 Pro", code = "23113RKC6G",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 23113RKC6G Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "poco-2311drk48g", brand = "POCO", model = "POCO X6 Pro 5G", code = "2311DRK48G",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 2311DRK48G Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC6", formFactor = "phone"
        ),
        Device(
            id = "poco-2311drk48i", brand = "POCO", model = "POCO X6 Pro 5G", code = "2311DRK48I",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 2311DRK48I Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC6", formFactor = "phone"
        ),
        Device(
            id = "poco-23122pcd1g", brand = "POCO", model = "POCO X6 5G", code = "23122PCD1G",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 23122PCD1G Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "poco-23122pcd1i", brand = "POCO", model = "POCO X6 5G", code = "23122PCD1I",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 23122PCD1I Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "poco-2312bpc51h", brand = "POCO", model = "POCO C61", code = "2312BPC51H",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; 2312BPC51H Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "poco-2312bpc51x", brand = "POCO", model = "POCO C61", code = "2312BPC51X",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; 2312BPC51X Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "poco-2312fpca6g", brand = "POCO", model = "POCO M6 Pro", code = "2312FPCA6G",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 2312FPCA6G Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "poco-2404apc5fg", brand = "POCO", model = "POCO M6", code = "2404APC5FG",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 2404APC5FG Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 610", formFactor = "phone"
        ),
        Device(
            id = "poco-24066pc95i", brand = "POCO", model = "POCO M6 Plus 5G", code = "24066PC95I",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 24066PC95I Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "poco-24069pc21g", brand = "POCO", model = "POCO F6", code = "24069PC21G",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 24069PC21G Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 735", formFactor = "phone"
        ),
        Device(
            id = "poco-24069pc21i", brand = "POCO", model = "POCO F6", code = "24069PC21I",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 24069PC21I Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 735", formFactor = "phone"
        ),
        Device(
            id = "poco-24095pcadg", brand = "POCO", model = "POCO X7", code = "24095PCADG",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 24095PCADG Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "poco-24095pcadi", brand = "POCO", model = "POCO X7", code = "24095PCADI",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 24095PCADI Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "poco-2409fpcc4i", brand = "POCO", model = "POCO M7 Pro 5G", code = "2409FPCC4I",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 2409FPCC4I Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "poco-24108pce2i", brand = "POCO", model = "POCO M7 5G", code = "24108PCE2I",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 24108PCE2I Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "poco-2410fpcc5g", brand = "POCO", model = "POCO C75", code = "2410FPCC5G",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 2410FPCC5G Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "poco-24116pcc1i", brand = "POCO", model = "POCO C75 5G", code = "24116PCC1I",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 24116PCC1I Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "poco-2412dpc0ag", brand = "POCO", model = "POCO X7 Pro", code = "2412DPC0AG",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 2412DPC0AG Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G720-Immortalis MC7", formFactor = "phone"
        ),
        Device(
            id = "poco-2412dpc0ai", brand = "POCO", model = "POCO X7 Pro", code = "2412DPC0AI",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 2412DPC0AI Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G720-Immortalis MC7", formFactor = "phone"
        ),
        Device(
            id = "poco-25028pc03g", brand = "POCO", model = "POCO C71", code = "25028PC03G",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; 25028PC03G Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "poco-25028pc03i", brand = "POCO", model = "POCO C71", code = "25028PC03I",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; 25028PC03I Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "poco-25057pc09i", brand = "POCO", model = "POCO M7 Plus 5G", code = "25057PC09I",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 25057PC09I Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "poco-25062pc34e", brand = "POCO", model = "POCO M7", code = "25062PC34E",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 25062PC34E Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "poco-25062pc34g", brand = "POCO", model = "POCO M7", code = "25062PC34G",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 25062PC34G Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "poco-25078pc3ee", brand = "POCO", model = "POCO C85", code = "25078PC3EE",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 25078PC3EE Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "poco-25078pc3eg", brand = "POCO", model = "POCO C85", code = "25078PC3EG",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 25078PC3EG Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "poco-2508cpc2bi", brand = "POCO", model = "POCO C85 5G", code = "2508CPC2BI",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 2508CPC2BI Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "poco-25128pc17g", brand = "POCO", model = "POCO C81 Pro", code = "25128PC17G",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 25128PC17G Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "poco-25128pc17i", brand = "POCO", model = "POCO C81", code = "25128PC17I",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 25128PC17I Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "poco-25128pc17l", brand = "POCO", model = "POCO C81 Pro", code = "25128PC17L",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 25128PC17L Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "poco-25128pc17y", brand = "POCO", model = "POCO C81 Pro", code = "25128PC17Y",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 25128PC17Y Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-21061119ag", brand = "Redmi", model = "Redmi 10 2022", code = "21061119AG",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 21061119AG Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-21061119al", brand = "Redmi", model = "Redmi 10 2022", code = "21061119AL",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 21061119AL Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-21061119dg", brand = "Redmi", model = "Redmi 10 2022", code = "21061119DG",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 21061119DG Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-21121119sg", brand = "Redmi", model = "Redmi 10 2022", code = "21121119SG",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 21121119SG Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-21121119vl", brand = "Redmi", model = "Redmi 10 2022", code = "21121119VL",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 21121119VL Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-22011119uy", brand = "Redmi", model = "Redmi 10 2022", code = "22011119UY",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 22011119UY Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-24117rk2cc", brand = "Redmi", model = "REDMI K80", code = "24117RK2CC",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 24117RK2CC Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "redmi-24122rkc7c", brand = "Redmi", model = "REDMI K80 Pro", code = "24122RKC7C",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 24122RKC7C Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "redmi-25057ra09c", brand = "Redmi", model = "REDMI Note 15R", code = "25057RA09C",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 25057RA09C Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-25060rk16c", brand = "Redmi", model = "REDMI K80 Ultra", code = "25060RK16C",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 25060RK16C Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G925-Immortalis MC12", formFactor = "phone"
        ),
        Device(
            id = "redmi-25080rabdc", brand = "Redmi", model = "REDMI Note 15 Pro", code = "25080RABDC",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 25080RABDC Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-25080rabdg", brand = "Redmi", model = "REDMI Note 15 Pro 5G", code = "25080RABDG",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 25080RABDG Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "redmi-25080rabdi", brand = "Redmi", model = "REDMI Note 15 Pro 5G", code = "25080RABDI",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 25080RABDI Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "redmi-25080rabdr", brand = "Redmi", model = "REDMI Note 15 Pro 5G", code = "25080RABDR",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 25080RABDR Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "redmi-2508crn2bi", brand = "Redmi", model = "REDMI 15C 5G", code = "2508CRN2BI",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 2508CRN2BI Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-25098ra98c", brand = "Redmi", model = "REDMI Note 15", code = "25098RA98C",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 25098RA98C Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-25098ra98g", brand = "Redmi", model = "REDMI Note 15 5G", code = "25098RA98G",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 25098RA98G Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-25098ra98i", brand = "Redmi", model = "REDMI Note 15 5G", code = "25098RA98I",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 25098RA98I Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-25098ra98t", brand = "Redmi", model = "REDMI Note 15 5G", code = "25098RA98T",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 25098RA98T Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-25100ra69g", brand = "Redmi", model = "REDMI Note 15 Pro", code = "25100RA69G",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 25100RA69G Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-25104radac", brand = "Redmi", model = "REDMI Note 15 Pro+", code = "25104RADAC",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 25104RADAC Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "redmi-2510dra23e", brand = "Redmi", model = "REDMI Note 15", code = "2510DRA23E",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 2510DRA23E Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-2510dra23g", brand = "Redmi", model = "REDMI Note 15", code = "2510DRA23G",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 2510DRA23G Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-2510dra23l", brand = "Redmi", model = "REDMI Note 15", code = "2510DRA23L",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 2510DRA23L Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-2510drk44c", brand = "Redmi", model = "REDMI K90", code = "2510DRK44C",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 2510DRK44C Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 840", formFactor = "phone"
        ),
        Device(
            id = "redmi-2510era8bc", brand = "Redmi", model = "REDMI Note 15 Pro+", code = "2510ERA8BC",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 2510ERA8BC Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "redmi-25128rn17a", brand = "Redmi", model = "REDMI A7 Pro", code = "25128RN17A",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 25128RN17A Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-25128rn17i", brand = "Redmi", model = "REDMI A7 Pro", code = "25128RN17I",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 25128RN17I Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-25128rn17l", brand = "Redmi", model = "REDMI A7 Pro", code = "25128RN17L",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 25128RN17L Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-25128rn17y", brand = "Redmi", model = "REDMI A7 Pro", code = "25128RN17Y",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 25128RN17Y Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-26012rn62a", brand = "Redmi", model = "REDMI Note 17", code = "26012RN62A",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 26012RN62A Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-26012rn62l", brand = "Redmi", model = "REDMI Note 17", code = "26012RN62L",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 26012RN62L Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-26012rn62y", brand = "Redmi", model = "REDMI Note 17", code = "26012RN62Y",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 26012RN62Y Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-26020rn1ai", brand = "Redmi", model = "REDMI A7 Pro 5G", code = "26020RN1AI",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 26020RN1AI Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MP1", formFactor = "phone"
        ),
        Device(
            id = "redmi-26020rnb4a", brand = "Redmi", model = "REDMI A7", code = "26020RNB4A",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 26020RNB4A Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-26020rnb4i", brand = "Redmi", model = "REDMI A7", code = "26020RNB4I",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 26020RNB4I Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-26020rnb4l", brand = "Redmi", model = "REDMI A7", code = "26020RNB4L",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 26020RNB4L Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-26021rn18c", brand = "Redmi", model = "REDMI Note 17", code = "26021RN18C",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 26021RN18C Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-26021rn18i", brand = "Redmi", model = "REDMI Note 17 5G", code = "26021RN18I",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 26021RN18I Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-26062rn92g", brand = "Redmi", model = "REDMI 17 5G", code = "26062RN92G",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 26062RN92G Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-26062rn92i", brand = "Redmi", model = "REDMI 17 5G", code = "26062RN92I",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 26062RN92I Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-2607dra18c", brand = "Redmi", model = "REDMI Note 17 Pro", code = "2607DRA18C",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 2607DRA18C Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-2607dra18g", brand = "Redmi", model = "REDMI Note 17 Pro 5G", code = "2607DRA18G",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 2607DRA18G Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "redmi-2607dra18i", brand = "Redmi", model = "REDMI Note 17 Pro 5G", code = "2607DRA18I",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 2607DRA18I Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "redmi-2607dra18t", brand = "Redmi", model = "REDMI Note 17 Pro 5G", code = "2607DRA18T",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 2607DRA18T Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "redmi-2607frneag", brand = "Redmi", model = "REDMI 17C 5G", code = "2607FRNEAG",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 2607FRNEAG Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-26081ra18g", brand = "Redmi", model = "REDMI Note 17 5G", code = "26081RA18G",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 26081RA18G Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "redmi-26081ra18l", brand = "Redmi", model = "REDMI Note 17 5G", code = "26081RA18L",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 26081RA18L Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sc-51c", brand = "Samsung", model = "Galaxy S22", code = "SC-51C",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SC-51C Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sc-51d", brand = "Samsung", model = "Galaxy S23", code = "SC-51D",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SC-51D Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sc-51e", brand = "Samsung", model = "Galaxy S24", code = "SC-51E",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SC-51E Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sc-51f", brand = "Samsung", model = "Galaxy S25", code = "SC-51F",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SC-51F Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sc-52c", brand = "Samsung", model = "Galaxy S22 Ultra", code = "SC-52C",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SC-52C Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sc-52d", brand = "Samsung", model = "Galaxy S23 Ultra", code = "SC-52D",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SC-52D Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sc-52e", brand = "Samsung", model = "Galaxy S24 Ultra", code = "SC-52E",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SC-52E Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sc-52f", brand = "Samsung", model = "Galaxy S25 Ultra", code = "SC-52F",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SC-52F Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sc-53c", brand = "Samsung", model = "Galaxy A53 5G", code = "SC-53C",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SC-53C Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sc-53d", brand = "Samsung", model = "Galaxy A54 5G", code = "SC-53D",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SC-53D Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sc-53e", brand = "Samsung", model = "Galaxy A55 5G", code = "SC-53E",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SC-53E Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Samsung", gpuRenderer = "Xclipse 530", formFactor = "phone"
        ),
        Device(
            id = "samsung-sc-53f", brand = "Samsung", model = "Galaxy A25 5G", code = "SC-53F",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SC-53F Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sc-54c", brand = "Samsung", model = "Galaxy Z Flip4", code = "SC-54C",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SC-54C Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sc-54d", brand = "Samsung", model = "Galaxy Z Flip5", code = "SC-54D",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SC-54D Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sc-54e", brand = "Samsung", model = "Galaxy Z Flip6", code = "SC-54E",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SC-54E Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sc-54f", brand = "Samsung", model = "Galaxy A36 5G", code = "SC-54F",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SC-54F Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "samsung-sc-55c", brand = "Samsung", model = "Galaxy Z Fold4", code = "SC-55C",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SC-55C Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sc-55d", brand = "Samsung", model = "Galaxy Z Fold5", code = "SC-55D",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SC-55D Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sc-55e", brand = "Samsung", model = "Galaxy Z Fold6", code = "SC-55E",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SC-55E Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sc-55f", brand = "Samsung", model = "Galaxy Z Flip7", code = "SC-55F",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SC-55F Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sc-56c", brand = "Samsung", model = "Galaxy A23 5G", code = "SC-56C",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SC-56C Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "samsung-sc-56f", brand = "Samsung", model = "Galaxy Z Fold7", code = "SC-56F",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SC-56F Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-scg13", brand = "Samsung", model = "Galaxy S22", code = "SCG13",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SCG13 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-scg14", brand = "Samsung", model = "Galaxy S22 Ultra", code = "SCG14",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SCG14 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-scg15", brand = "Samsung", model = "Galaxy A53 5G", code = "SCG15",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SCG15 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-scg16", brand = "Samsung", model = "Galaxy Z Fold4", code = "SCG16",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SCG16 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-scg17", brand = "Samsung", model = "Galaxy Z Flip4", code = "SCG17",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SCG17 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-scg18", brand = "Samsung", model = "Galaxy A23 5G", code = "SCG18",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SCG18 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "samsung-scg19", brand = "Samsung", model = "Galaxy S23", code = "SCG19",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SCG19 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-scg20", brand = "Samsung", model = "Galaxy S23 Ultra", code = "SCG20",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SCG20 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-scg21", brand = "Samsung", model = "Galaxy A54 5G", code = "SCG21",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SCG21 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-scg22", brand = "Samsung", model = "Galaxy Z Fold5", code = "SCG22",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SCG22 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-scg23", brand = "Samsung", model = "Galaxy Z Flip5", code = "SCG23",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SCG23 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-scg24", brand = "Samsung", model = "Galaxy S23 FE", code = "SCG24",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SCG24 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Samsung", gpuRenderer = "Xclipse 920", formFactor = "phone"
        ),
        Device(
            id = "samsung-scg25", brand = "Samsung", model = "Galaxy S24", code = "SCG25",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SCG25 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-scg26", brand = "Samsung", model = "Galaxy S24 Ultra", code = "SCG26",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SCG26 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-scg27", brand = "Samsung", model = "Galaxy A55 5G", code = "SCG27",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SCG27 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Samsung", gpuRenderer = "Xclipse 530", formFactor = "phone"
        ),
        Device(
            id = "samsung-scg28", brand = "Samsung", model = "Galaxy Z Fold6", code = "SCG28",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SCG28 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-scg29", brand = "Samsung", model = "Galaxy Z Flip6", code = "SCG29",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SCG29 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-scg30", brand = "Samsung", model = "Galaxy S24 FE", code = "SCG30",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SCG30 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Samsung", gpuRenderer = "Xclipse 940", formFactor = "phone"
        ),
        Device(
            id = "samsung-scg31", brand = "Samsung", model = "Galaxy S25", code = "SCG31",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SCG31 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-scg32", brand = "Samsung", model = "Galaxy S25 Ultra", code = "SCG32",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SCG32 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-scg33", brand = "Samsung", model = "Galaxy A25 5G", code = "SCG33",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SCG33 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-scg34", brand = "Samsung", model = "Galaxy Z Fold7", code = "SCG34",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SCG34 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-scg35", brand = "Samsung", model = "Galaxy Z Flip7", code = "SCG35",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SCG35 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a135f", brand = "Samsung", model = "Galaxy A13", code = "SM-A135F",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A135F Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MP1", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a135m", brand = "Samsung", model = "Galaxy A13", code = "SM-A135M",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A135M Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MP1", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a135n", brand = "Samsung", model = "Galaxy A13", code = "SM-A135N",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A135N Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MP1", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a135u", brand = "Samsung", model = "Galaxy A13", code = "SM-A135U",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A135U Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MP1", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a135u1", brand = "Samsung", model = "Galaxy A13", code = "SM-A135U1",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A135U1 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MP1", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a136b", brand = "Samsung", model = "Galaxy A13 5G", code = "SM-A136B",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A136B Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a136m", brand = "Samsung", model = "Galaxy A13 5G", code = "SM-A136M",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A136M Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a136s", brand = "Samsung", model = "Galaxy A13 5G", code = "SM-A136S",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A136S Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a136u", brand = "Samsung", model = "Galaxy A13 5G", code = "SM-A136U",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A136U Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a136u1", brand = "Samsung", model = "Galaxy A13 5G", code = "SM-A136U1",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A136U1 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a136w", brand = "Samsung", model = "Galaxy A13 5G", code = "SM-A136W",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A136W Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a137f", brand = "Samsung", model = "Galaxy A13", code = "SM-A137F",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A137F Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MP1", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a145f", brand = "Samsung", model = "Galaxy A14", code = "SM-A145F",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A145F Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MP1", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a145fb", brand = "Samsung", model = "Galaxy A14", code = "SM-A145FB",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A145FB Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MP1", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a145m", brand = "Samsung", model = "Galaxy A14", code = "SM-A145M",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A145M Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MP1", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a145mb", brand = "Samsung", model = "Galaxy A14", code = "SM-A145MB",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A145MB Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MP1", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a145p", brand = "Samsung", model = "Galaxy A14", code = "SM-A145P",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A145P Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MP1", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a145r", brand = "Samsung", model = "Galaxy A14", code = "SM-A145R",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A145R Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MP1", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a146b", brand = "Samsung", model = "Galaxy A14 5G", code = "SM-A146B",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A146B Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a146m", brand = "Samsung", model = "Galaxy A14 5G", code = "SM-A146M",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A146M Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a146p", brand = "Samsung", model = "Galaxy A14 5G", code = "SM-A146P",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A146P Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a146u", brand = "Samsung", model = "Galaxy A14 5G", code = "SM-A146U",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A146U Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a146u1", brand = "Samsung", model = "Galaxy A14 5G", code = "SM-A146U1",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A146U1 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a146w", brand = "Samsung", model = "Galaxy A14 5G", code = "SM-A146W",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A146W Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a155f", brand = "Samsung", model = "Galaxy A15", code = "SM-A155F",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A155F Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a155m", brand = "Samsung", model = "Galaxy A15", code = "SM-A155M",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A155M Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a155n", brand = "Samsung", model = "Galaxy A15", code = "SM-A155N",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A155N Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a1560", brand = "Samsung", model = "Galaxy A15 5G", code = "SM-A1560",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A1560 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a156b", brand = "Samsung", model = "Galaxy A15 5G", code = "SM-A156B",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A156B Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a156e", brand = "Samsung", model = "Galaxy A15 5G", code = "SM-A156E",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A156E Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a156m", brand = "Samsung", model = "Galaxy A15 5G", code = "SM-A156M",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A156M Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a156u", brand = "Samsung", model = "Galaxy A15 5G", code = "SM-A156U",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A156U Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a156u1", brand = "Samsung", model = "Galaxy A15 5G", code = "SM-A156U1",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A156U1 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a156w", brand = "Samsung", model = "Galaxy A15 5G", code = "SM-A156W",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A156W Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a165f", brand = "Samsung", model = "Galaxy A16", code = "SM-A165F",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-A165F Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a165m", brand = "Samsung", model = "Galaxy A16", code = "SM-A165M",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-A165M Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a165n", brand = "Samsung", model = "Galaxy A16", code = "SM-A165N",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-A165N Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a1660", brand = "Samsung", model = "Galaxy A16 5G", code = "SM-A1660",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-A1660 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a166b", brand = "Samsung", model = "Galaxy A16 5G", code = "SM-A166B",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-A166B Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a166e", brand = "Samsung", model = "Galaxy A16 5G", code = "SM-A166E",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-A166E Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a166m", brand = "Samsung", model = "Galaxy A16 5G", code = "SM-A166M",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-A166M Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a166p", brand = "Samsung", model = "Galaxy A16 5G", code = "SM-A166P",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-A166P Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a166u", brand = "Samsung", model = "Galaxy A16 5G", code = "SM-A166U",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-A166U Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a166u1", brand = "Samsung", model = "Galaxy A16 5G", code = "SM-A166U1",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-A166U1 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a166w", brand = "Samsung", model = "Galaxy A16 5G", code = "SM-A166W",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-A166W Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a175f", brand = "Samsung", model = "Galaxy A17", code = "SM-A175F",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-A175F Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a175n", brand = "Samsung", model = "Galaxy A17", code = "SM-A175N",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-A175N Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a1760", brand = "Samsung", model = "Galaxy A17 5G", code = "SM-A1760",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-A1760 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a176b", brand = "Samsung", model = "Galaxy A17 5G", code = "SM-A176B",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-A176B Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a176u", brand = "Samsung", model = "Galaxy A17 5G", code = "SM-A176U",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-A176U Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a176u1", brand = "Samsung", model = "Galaxy A17 5G", code = "SM-A176U1",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-A176U1 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a176w", brand = "Samsung", model = "Galaxy A17 5G", code = "SM-A176W",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-A176W Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a233c", brand = "Samsung", model = "Galaxy A23 5G", code = "SM-A233C",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A233C Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a235f", brand = "Samsung", model = "Galaxy A23", code = "SM-A235F",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A235F Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 610", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a235m", brand = "Samsung", model = "Galaxy A23", code = "SM-A235M",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A235M Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 610", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a235n", brand = "Samsung", model = "Galaxy A23", code = "SM-A235N",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A235N Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 610", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a2360", brand = "Samsung", model = "Galaxy A23 5G", code = "SM-A2360",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A2360 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a236b", brand = "Samsung", model = "Galaxy A23 5G", code = "SM-A236B",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A236B Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a236e", brand = "Samsung", model = "Galaxy A23 5G", code = "SM-A236E",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A236E Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a236m", brand = "Samsung", model = "Galaxy A23 5G", code = "SM-A236M",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A236M Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a236u", brand = "Samsung", model = "Galaxy A23 5G", code = "SM-A236U",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A236U Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a236u1", brand = "Samsung", model = "Galaxy A23 5G", code = "SM-A236U1",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A236U1 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a245f", brand = "Samsung", model = "Galaxy A24", code = "SM-A245F",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A245F Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a245m", brand = "Samsung", model = "Galaxy A24", code = "SM-A245M",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A245M Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a245n", brand = "Samsung", model = "Galaxy A24", code = "SM-A245N",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A245N Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a253c", brand = "Samsung", model = "Galaxy A25 5G", code = "SM-A253C",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A253C Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a253q", brand = "Samsung", model = "Galaxy A25 5G", code = "SM-A253Q",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A253Q Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a253z", brand = "Samsung", model = "Galaxy A25 5G", code = "SM-A253Z",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A253Z Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a2560", brand = "Samsung", model = "Galaxy A25 5G", code = "SM-A2560",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A2560 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a256b", brand = "Samsung", model = "Galaxy A25 5G", code = "SM-A256B",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A256B Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a256e", brand = "Samsung", model = "Galaxy A25 5G", code = "SM-A256E",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A256E Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a256n", brand = "Samsung", model = "Galaxy A25 5G", code = "SM-A256N",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A256N Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a256u", brand = "Samsung", model = "Galaxy A25 5G", code = "SM-A256U",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A256U Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a256u1", brand = "Samsung", model = "Galaxy A25 5G", code = "SM-A256U1",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A256U1 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a266b", brand = "Samsung", model = "Galaxy A26 5G", code = "SM-A266B",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-A266B Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a266m", brand = "Samsung", model = "Galaxy A26 5G", code = "SM-A266M",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-A266M Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a266u", brand = "Samsung", model = "Galaxy A26 5G", code = "SM-A266U",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-A266U Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a266u1", brand = "Samsung", model = "Galaxy A26 5G", code = "SM-A266U1",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-A266U1 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a3360", brand = "Samsung", model = "Galaxy A33 5G", code = "SM-A3360",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A3360 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a336b", brand = "Samsung", model = "Galaxy A33 5G", code = "SM-A336B",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A336B Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a336e", brand = "Samsung", model = "Galaxy A33 5G", code = "SM-A336E",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A336E Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a336m", brand = "Samsung", model = "Galaxy A33 5G", code = "SM-A336M",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A336M Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a336n", brand = "Samsung", model = "Galaxy A33 5G", code = "SM-A336N",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A336N Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a3460", brand = "Samsung", model = "Galaxy A34 5G", code = "SM-A3460",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A3460 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a346b", brand = "Samsung", model = "Galaxy A34 5G", code = "SM-A346B",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A346B Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a346e", brand = "Samsung", model = "Galaxy A34 5G", code = "SM-A346E",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A346E Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a346m", brand = "Samsung", model = "Galaxy A34 5G", code = "SM-A346M",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A346M Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a346n", brand = "Samsung", model = "Galaxy A34 5G", code = "SM-A346N",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A346N Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a3560", brand = "Samsung", model = "Galaxy A35 5G", code = "SM-A3560",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-A3560 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a356b", brand = "Samsung", model = "Galaxy A35 5G", code = "SM-A356B",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-A356B Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a356e", brand = "Samsung", model = "Galaxy A35 5G", code = "SM-A356E",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-A356E Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a356n", brand = "Samsung", model = "Galaxy A35 5G", code = "SM-A356N",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-A356N Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a356u", brand = "Samsung", model = "Galaxy A35 5G", code = "SM-A356U",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-A356U Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a356u1", brand = "Samsung", model = "Galaxy A35 5G", code = "SM-A356U1",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-A356U1 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a356w", brand = "Samsung", model = "Galaxy A35 5G", code = "SM-A356W",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-A356W Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a3660", brand = "Samsung", model = "Galaxy A36 5G", code = "SM-A3660",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-A3660 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a366b", brand = "Samsung", model = "Galaxy A36 5G", code = "SM-A366B",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-A366B Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a366e", brand = "Samsung", model = "Galaxy A36 5G", code = "SM-A366E",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-A366E Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a366n", brand = "Samsung", model = "Galaxy A36 5G", code = "SM-A366N",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-A366N Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a366q", brand = "Samsung", model = "Galaxy A36 5G", code = "SM-A366Q",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-A366Q Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a366u", brand = "Samsung", model = "Galaxy A36 5G", code = "SM-A366U",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-A366U Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a366u1", brand = "Samsung", model = "Galaxy A36 5G", code = "SM-A366U1",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-A366U1 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a366w", brand = "Samsung", model = "Galaxy A36 5G", code = "SM-A366W",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-A366W Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a5360", brand = "Samsung", model = "Galaxy A53 5G", code = "SM-A5360",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A5360 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a536b", brand = "Samsung", model = "Galaxy A53 5G", code = "SM-A536B",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A536B Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a536e", brand = "Samsung", model = "Galaxy A53 5G", code = "SM-A536E",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A536E Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a536n", brand = "Samsung", model = "Galaxy A53 5G", code = "SM-A536N",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A536N Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a536u", brand = "Samsung", model = "Galaxy A53 5G", code = "SM-A536U",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A536U Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a536u1", brand = "Samsung", model = "Galaxy A53 5G", code = "SM-A536U1",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A536U1 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a536w", brand = "Samsung", model = "Galaxy A53 5G", code = "SM-A536W",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A536W Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a5460", brand = "Samsung", model = "Galaxy A54 5G", code = "SM-A5460",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A5460 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a546b", brand = "Samsung", model = "Galaxy A54 5G", code = "SM-A546B",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A546B Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a546e", brand = "Samsung", model = "Galaxy A54 5G", code = "SM-A546E",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A546E Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a546u", brand = "Samsung", model = "Galaxy A54 5G", code = "SM-A546U",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A546U Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a546u1", brand = "Samsung", model = "Galaxy A54 5G", code = "SM-A546U1",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A546U1 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a546v", brand = "Samsung", model = "Galaxy A54 5G", code = "SM-A546V",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A546V Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a546w", brand = "Samsung", model = "Galaxy A54 5G", code = "SM-A546W",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-A546W Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a5560", brand = "Samsung", model = "Galaxy A55 5G", code = "SM-A5560",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-A5560 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Samsung", gpuRenderer = "Xclipse 530", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a556b", brand = "Samsung", model = "Galaxy A55 5G", code = "SM-A556B",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-A556B Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Samsung", gpuRenderer = "Xclipse 530", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a556e", brand = "Samsung", model = "Galaxy A55 5G", code = "SM-A556E",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-A556E Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Samsung", gpuRenderer = "Xclipse 530", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a5660", brand = "Samsung", model = "Galaxy A56 5G", code = "SM-A5660",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-A5660 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Samsung", gpuRenderer = "Xclipse 540", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a566b", brand = "Samsung", model = "Galaxy A56 5G", code = "SM-A566B",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-A566B Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Samsung", gpuRenderer = "Xclipse 540", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a566e", brand = "Samsung", model = "Galaxy A56 5G", code = "SM-A566E",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-A566E Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Samsung", gpuRenderer = "Xclipse 540", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a566u1", brand = "Samsung", model = "Galaxy A56 5G", code = "SM-A566U1",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-A566U1 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Samsung", gpuRenderer = "Xclipse 540", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a566w", brand = "Samsung", model = "Galaxy A56 5G", code = "SM-A566W",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-A566W Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Samsung", gpuRenderer = "Xclipse 540", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-a736b", brand = "Samsung", model = "Galaxy A73 5G", code = "SM-A736B",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-A736B Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 642L", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-e145f", brand = "Samsung", model = "Galaxy F14", code = "SM-E145F",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-E145F Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MP1", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-e146b", brand = "Samsung", model = "Galaxy F14 5G", code = "SM-E146B",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-E146B Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-e156b", brand = "Samsung", model = "Galaxy F15 5G", code = "SM-E156B",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-E156B Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-e166p", brand = "Samsung", model = "Galaxy F16 5G", code = "SM-E166P",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-E166P Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-e546b", brand = "Samsung", model = "Galaxy F54 5G", code = "SM-E546B",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-E546B Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-e556b", brand = "Samsung", model = "Galaxy F55 5G", code = "SM-E556B",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-E556B Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 644", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f7210", brand = "Samsung", model = "Galaxy Z Flip4", code = "SM-F7210",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-F7210 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f721b", brand = "Samsung", model = "Galaxy Z Flip4", code = "SM-F721B",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-F721B Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f721c", brand = "Samsung", model = "Galaxy Z Flip4", code = "SM-F721C",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-F721C Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f721n", brand = "Samsung", model = "Galaxy Z Flip4", code = "SM-F721N",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-F721N Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f721u", brand = "Samsung", model = "Galaxy Z Flip4", code = "SM-F721U",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-F721U Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f721u1", brand = "Samsung", model = "Galaxy Z Flip4", code = "SM-F721U1",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-F721U1 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f721w", brand = "Samsung", model = "Galaxy Z Flip4", code = "SM-F721W",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-F721W Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f7310", brand = "Samsung", model = "Galaxy Z Flip5", code = "SM-F7310",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-F7310 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f731b", brand = "Samsung", model = "Galaxy Z Flip5", code = "SM-F731B",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-F731B Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f731n", brand = "Samsung", model = "Galaxy Z Flip5", code = "SM-F731N",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-F731N Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f731q", brand = "Samsung", model = "Galaxy Z Flip5", code = "SM-F731Q",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-F731Q Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f731u", brand = "Samsung", model = "Galaxy Z Flip5", code = "SM-F731U",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-F731U Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f731u1", brand = "Samsung", model = "Galaxy Z Flip5", code = "SM-F731U1",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-F731U1 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f731w", brand = "Samsung", model = "Galaxy Z Flip5", code = "SM-F731W",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-F731W Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f7410", brand = "Samsung", model = "Galaxy Z Flip6", code = "SM-F7410",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-F7410 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f741b", brand = "Samsung", model = "Galaxy Z Flip6", code = "SM-F741B",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-F741B Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f741n", brand = "Samsung", model = "Galaxy Z Flip6", code = "SM-F741N",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-F741N Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f741q", brand = "Samsung", model = "Galaxy Z Flip6", code = "SM-F741Q",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-F741Q Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f741u", brand = "Samsung", model = "Galaxy Z Flip6", code = "SM-F741U",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-F741U Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f741u1", brand = "Samsung", model = "Galaxy Z Flip6", code = "SM-F741U1",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-F741U1 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f741w", brand = "Samsung", model = "Galaxy Z Flip6", code = "SM-F741W",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-F741W Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f7660", brand = "Samsung", model = "Galaxy Z Flip7", code = "SM-F7660",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-F7660 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f766b", brand = "Samsung", model = "Galaxy Z Flip7", code = "SM-F766B",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-F766B Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f766n", brand = "Samsung", model = "Galaxy Z Flip7", code = "SM-F766N",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-F766N Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f766q", brand = "Samsung", model = "Galaxy Z Flip7", code = "SM-F766Q",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-F766Q Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f766u", brand = "Samsung", model = "Galaxy Z Flip7", code = "SM-F766U",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-F766U Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f766u1", brand = "Samsung", model = "Galaxy Z Flip7", code = "SM-F766U1",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-F766U1 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f766w", brand = "Samsung", model = "Galaxy Z Flip7", code = "SM-F766W",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-F766W Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f766z", brand = "Samsung", model = "Galaxy Z Flip7", code = "SM-F766Z",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-F766Z Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f9360", brand = "Samsung", model = "Galaxy Z Fold4", code = "SM-F9360",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-F9360 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f936b", brand = "Samsung", model = "Galaxy Z Fold4", code = "SM-F936B",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-F936B Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f936n", brand = "Samsung", model = "Galaxy Z Fold4", code = "SM-F936N",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-F936N Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f936u", brand = "Samsung", model = "Galaxy Z Fold4", code = "SM-F936U",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-F936U Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f936u1", brand = "Samsung", model = "Galaxy Z Fold4", code = "SM-F936U1",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-F936U1 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f936w", brand = "Samsung", model = "Galaxy Z Fold4", code = "SM-F936W",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-F936W Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f9460", brand = "Samsung", model = "Galaxy Z Fold5", code = "SM-F9460",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-F9460 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f946b", brand = "Samsung", model = "Galaxy Z Fold5", code = "SM-F946B",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-F946B Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f946n", brand = "Samsung", model = "Galaxy Z Fold5", code = "SM-F946N",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-F946N Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f946q", brand = "Samsung", model = "Galaxy Z Fold5", code = "SM-F946Q",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-F946Q Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f946u", brand = "Samsung", model = "Galaxy Z Fold5", code = "SM-F946U",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-F946U Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f946u1", brand = "Samsung", model = "Galaxy Z Fold5", code = "SM-F946U1",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-F946U1 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f946w", brand = "Samsung", model = "Galaxy Z Fold5", code = "SM-F946W",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-F946W Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f9560", brand = "Samsung", model = "Galaxy Z Fold6", code = "SM-F9560",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-F9560 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f956b", brand = "Samsung", model = "Galaxy Z Fold6", code = "SM-F956B",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-F956B Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f956n", brand = "Samsung", model = "Galaxy Z Fold6", code = "SM-F956N",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-F956N Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f956q", brand = "Samsung", model = "Galaxy Z Fold6", code = "SM-F956Q",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-F956Q Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f956u", brand = "Samsung", model = "Galaxy Z Fold6", code = "SM-F956U",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-F956U Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f956u1", brand = "Samsung", model = "Galaxy Z Fold6", code = "SM-F956U1",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-F956U1 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f956w", brand = "Samsung", model = "Galaxy Z Fold6", code = "SM-F956W",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-F956W Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f9660", brand = "Samsung", model = "Galaxy Z Fold7", code = "SM-F9660",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-F9660 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f966b", brand = "Samsung", model = "Galaxy Z Fold7", code = "SM-F966B",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-F966B Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f966n", brand = "Samsung", model = "Galaxy Z Fold7", code = "SM-F966N",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-F966N Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f966q", brand = "Samsung", model = "Galaxy Z Fold7", code = "SM-F966Q",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-F966Q Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f966u", brand = "Samsung", model = "Galaxy Z Fold7", code = "SM-F966U",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-F966U Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f966u1", brand = "Samsung", model = "Galaxy Z Fold7", code = "SM-F966U1",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-F966U1 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f966w", brand = "Samsung", model = "Galaxy Z Fold7", code = "SM-F966W",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-F966W Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-f966z", brand = "Samsung", model = "Galaxy Z Fold7", code = "SM-F966Z",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-F966Z Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-g736b", brand = "Samsung", model = "Galaxy XCover6 Pro", code = "SM-G736B",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-G736B Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 642L", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-g736u", brand = "Samsung", model = "Galaxy XCover6 Pro", code = "SM-G736U",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-G736U Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 642L", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-g736u1", brand = "Samsung", model = "Galaxy XCover6 Pro", code = "SM-G736U1",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-G736U1 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 642L", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-g736w", brand = "Samsung", model = "Galaxy XCover6 Pro", code = "SM-G736W",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-G736W Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 642L", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-g766b", brand = "Samsung", model = "Galaxy XCover7 Pro", code = "SM-G766B",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-G766B Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-g766n", brand = "Samsung", model = "Galaxy XCover7 Pro", code = "SM-G766N",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-G766N Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-g766u", brand = "Samsung", model = "Galaxy XCover7 Pro", code = "SM-G766U",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-G766U Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-g766u1", brand = "Samsung", model = "Galaxy XCover7 Pro", code = "SM-G766U1",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-G766U1 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-g766w", brand = "Samsung", model = "Galaxy XCover7 Pro", code = "SM-G766W",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-G766W Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-m145f", brand = "Samsung", model = "Galaxy M14", code = "SM-M145F",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-M145F Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MP1", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-m146b", brand = "Samsung", model = "Galaxy M14 5G", code = "SM-M146B",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-M146B Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-m156b", brand = "Samsung", model = "Galaxy M15 5G", code = "SM-M156B",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-M156B Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-m166p", brand = "Samsung", model = "Galaxy M16 5G", code = "SM-M166P",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-M166P Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-m336b", brand = "Samsung", model = "Galaxy M33 5G", code = "SM-M336B",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-M336B Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-m336bu", brand = "Samsung", model = "Galaxy M33 5G", code = "SM-M336BU",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-M336BU Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-m346b", brand = "Samsung", model = "Galaxy M34 5G", code = "SM-M346B",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-M346B Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-m346b1", brand = "Samsung", model = "Galaxy M34 5G", code = "SM-M346B1",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-M346B1 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-m346b2", brand = "Samsung", model = "Galaxy M34 5G", code = "SM-M346B2",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-M346B2 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-m356b", brand = "Samsung", model = "Galaxy M35 5G", code = "SM-M356B",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-M356B Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-m536b", brand = "Samsung", model = "Galaxy M53 5G", code = "SM-M536B",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-M536B Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-m546b", brand = "Samsung", model = "Galaxy M54 5G", code = "SM-M546B",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-M546B Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 660", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-m556b", brand = "Samsung", model = "Galaxy M55 5G", code = "SM-M556B",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-M556B Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 644", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-m556e", brand = "Samsung", model = "Galaxy M55 5G", code = "SM-M556E",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-M556E Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 644", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s136dl", brand = "Samsung", model = "Galaxy A13 5G", code = "SM-S136DL",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-S136DL Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s146vl", brand = "Samsung", model = "Galaxy A14 5G", code = "SM-S146VL",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S146VL Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s156v", brand = "Samsung", model = "Galaxy A15 5G", code = "SM-S156V",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S156V Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s166v", brand = "Samsung", model = "Galaxy A16 5G", code = "SM-S166V",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S166V Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s176v", brand = "Samsung", model = "Galaxy A17 5G", code = "SM-S176V",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-S176V Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s236dl", brand = "Samsung", model = "Galaxy A23 5G", code = "SM-S236DL",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-S236DL Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s237vl", brand = "Samsung", model = "Galaxy A23 5G", code = "SM-S237VL",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-S237VL Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s256vl", brand = "Samsung", model = "Galaxy A25 5G", code = "SM-S256VL",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S256VL Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s266v", brand = "Samsung", model = "Galaxy A26 5G", code = "SM-S266V",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-S266V Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s356v", brand = "Samsung", model = "Galaxy A35 5G", code = "SM-S356V",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S356V Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s366v", brand = "Samsung", model = "Galaxy A36 5G", code = "SM-S366V",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-S366V Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s536dl", brand = "Samsung", model = "Galaxy A53 5G", code = "SM-S536DL",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-S536DL Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s546vl", brand = "Samsung", model = "Galaxy A54 5G", code = "SM-S546VL",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S546VL Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s7110", brand = "Samsung", model = "Galaxy S23 FE", code = "SM-S7110",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S7110 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Samsung", gpuRenderer = "Xclipse 920", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s711b", brand = "Samsung", model = "Galaxy S23 FE", code = "SM-S711B",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S711B Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Samsung", gpuRenderer = "Xclipse 920", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s711n", brand = "Samsung", model = "Galaxy S23 FE", code = "SM-S711N",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S711N Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Samsung", gpuRenderer = "Xclipse 920", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s711u", brand = "Samsung", model = "Galaxy S23 FE", code = "SM-S711U",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S711U Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s711u1", brand = "Samsung", model = "Galaxy S23 FE", code = "SM-S711U1",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S711U1 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s711w", brand = "Samsung", model = "Galaxy S23 FE", code = "SM-S711W",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S711W Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s7210", brand = "Samsung", model = "Galaxy S24 FE", code = "SM-S7210",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S7210 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Samsung", gpuRenderer = "Xclipse 940", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s721b", brand = "Samsung", model = "Galaxy S24 FE", code = "SM-S721B",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S721B Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Samsung", gpuRenderer = "Xclipse 940", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s721n", brand = "Samsung", model = "Galaxy S24 FE", code = "SM-S721N",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S721N Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Samsung", gpuRenderer = "Xclipse 940", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s721q", brand = "Samsung", model = "Galaxy S24 FE", code = "SM-S721Q",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S721Q Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Samsung", gpuRenderer = "Xclipse 940", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s721u", brand = "Samsung", model = "Galaxy S24 FE", code = "SM-S721U",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S721U Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Samsung", gpuRenderer = "Xclipse 940", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s721u1", brand = "Samsung", model = "Galaxy S24 FE", code = "SM-S721U1",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S721U1 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Samsung", gpuRenderer = "Xclipse 940", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s721w", brand = "Samsung", model = "Galaxy S24 FE", code = "SM-S721W",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S721W Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Samsung", gpuRenderer = "Xclipse 940", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s9010", brand = "Samsung", model = "Galaxy S22", code = "SM-S9010",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-S9010 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s901b", brand = "Samsung", model = "Galaxy S22", code = "SM-S901B",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-S901B Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Samsung", gpuRenderer = "Xclipse 920", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s901e", brand = "Samsung", model = "Galaxy S22", code = "SM-S901E",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-S901E Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s901n", brand = "Samsung", model = "Galaxy S22", code = "SM-S901N",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-S901N Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s901u", brand = "Samsung", model = "Galaxy S22", code = "SM-S901U",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-S901U Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s901u1", brand = "Samsung", model = "Galaxy S22", code = "SM-S901U1",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-S901U1 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s901w", brand = "Samsung", model = "Galaxy S22", code = "SM-S901W",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-S901W Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s9060", brand = "Samsung", model = "Galaxy S22+", code = "SM-S9060",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-S9060 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s906b", brand = "Samsung", model = "Galaxy S22+", code = "SM-S906B",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-S906B Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Samsung", gpuRenderer = "Xclipse 920", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s906e", brand = "Samsung", model = "Galaxy S22+", code = "SM-S906E",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-S906E Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s906n", brand = "Samsung", model = "Galaxy S22+", code = "SM-S906N",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-S906N Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s906u", brand = "Samsung", model = "Galaxy S22+", code = "SM-S906U",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-S906U Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s906u1", brand = "Samsung", model = "Galaxy S22+", code = "SM-S906U1",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-S906U1 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s906w", brand = "Samsung", model = "Galaxy S22+", code = "SM-S906W",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-S906W Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s9080", brand = "Samsung", model = "Galaxy S22 Ultra", code = "SM-S9080",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-S9080 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s908b", brand = "Samsung", model = "Galaxy S22 Ultra", code = "SM-S908B",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-S908B Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Samsung", gpuRenderer = "Xclipse 920", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s908e", brand = "Samsung", model = "Galaxy S22 Ultra", code = "SM-S908E",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-S908E Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s908n", brand = "Samsung", model = "Galaxy S22 Ultra", code = "SM-S908N",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-S908N Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s908u", brand = "Samsung", model = "Galaxy S22 Ultra", code = "SM-S908U",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-S908U Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s908u1", brand = "Samsung", model = "Galaxy S22 Ultra", code = "SM-S908U1",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-S908U1 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s908w", brand = "Samsung", model = "Galaxy S22 Ultra", code = "SM-S908W",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-S908W Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s9110", brand = "Samsung", model = "Galaxy S23", code = "SM-S9110",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S9110 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s911b", brand = "Samsung", model = "Galaxy S23", code = "SM-S911B",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S911B Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s911c", brand = "Samsung", model = "Galaxy S23", code = "SM-S911C",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S911C Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s911n", brand = "Samsung", model = "Galaxy S23", code = "SM-S911N",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S911N Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s911u", brand = "Samsung", model = "Galaxy S23", code = "SM-S911U",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S911U Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s911u1", brand = "Samsung", model = "Galaxy S23", code = "SM-S911U1",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S911U1 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s911w", brand = "Samsung", model = "Galaxy S23", code = "SM-S911W",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S911W Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s9160", brand = "Samsung", model = "Galaxy S23+", code = "SM-S9160",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S9160 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s916b", brand = "Samsung", model = "Galaxy S23+", code = "SM-S916B",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S916B Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s916n", brand = "Samsung", model = "Galaxy S23+", code = "SM-S916N",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S916N Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s916u", brand = "Samsung", model = "Galaxy S23+", code = "SM-S916U",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S916U Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s916u1", brand = "Samsung", model = "Galaxy S23+", code = "SM-S916U1",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S916U1 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s916w", brand = "Samsung", model = "Galaxy S23+", code = "SM-S916W",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S916W Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s9180", brand = "Samsung", model = "Galaxy S23 Ultra", code = "SM-S9180",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S9180 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s918b", brand = "Samsung", model = "Galaxy S23 Ultra", code = "SM-S918B",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S918B Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s918n", brand = "Samsung", model = "Galaxy S23 Ultra", code = "SM-S918N",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S918N Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s918q", brand = "Samsung", model = "Galaxy S23 Ultra", code = "SM-S918Q",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S918Q Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s918u", brand = "Samsung", model = "Galaxy S23 Ultra", code = "SM-S918U",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S918U Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s918u1", brand = "Samsung", model = "Galaxy S23 Ultra", code = "SM-S918U1",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S918U1 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s918w", brand = "Samsung", model = "Galaxy S23 Ultra", code = "SM-S918W",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S918W Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s9210", brand = "Samsung", model = "Galaxy S24", code = "SM-S9210",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S9210 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s921b", brand = "Samsung", model = "Galaxy S24", code = "SM-S921B",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S921B Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Samsung", gpuRenderer = "Xclipse 940", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s921e", brand = "Samsung", model = "Galaxy S24", code = "SM-S921E",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S921E Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Samsung", gpuRenderer = "Xclipse 940", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s921n", brand = "Samsung", model = "Galaxy S24", code = "SM-S921N",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S921N Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Samsung", gpuRenderer = "Xclipse 940", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s921q", brand = "Samsung", model = "Galaxy S24", code = "SM-S921Q",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S921Q Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s921u", brand = "Samsung", model = "Galaxy S24", code = "SM-S921U",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S921U Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s921u1", brand = "Samsung", model = "Galaxy S24", code = "SM-S921U1",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S921U1 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s921w", brand = "Samsung", model = "Galaxy S24", code = "SM-S921W",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S921W Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s9260", brand = "Samsung", model = "Galaxy S24+", code = "SM-S9260",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S9260 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s926b", brand = "Samsung", model = "Galaxy S24+", code = "SM-S926B",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S926B Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Samsung", gpuRenderer = "Xclipse 940", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s926n", brand = "Samsung", model = "Galaxy S24+", code = "SM-S926N",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S926N Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Samsung", gpuRenderer = "Xclipse 940", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s926u", brand = "Samsung", model = "Galaxy S24+", code = "SM-S926U",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S926U Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s926u1", brand = "Samsung", model = "Galaxy S24+", code = "SM-S926U1",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S926U1 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s926w", brand = "Samsung", model = "Galaxy S24+", code = "SM-S926W",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S926W Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s9280", brand = "Samsung", model = "Galaxy S24 Ultra", code = "SM-S9280",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S9280 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s928b", brand = "Samsung", model = "Galaxy S24 Ultra", code = "SM-S928B",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S928B Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s928n", brand = "Samsung", model = "Galaxy S24 Ultra", code = "SM-S928N",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S928N Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s928q", brand = "Samsung", model = "Galaxy S24 Ultra", code = "SM-S928Q",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S928Q Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s928u", brand = "Samsung", model = "Galaxy S24 Ultra", code = "SM-S928U",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S928U Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s928u1", brand = "Samsung", model = "Galaxy S24 Ultra", code = "SM-S928U1",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S928U1 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s928w", brand = "Samsung", model = "Galaxy S24 Ultra", code = "SM-S928W",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S928W Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s9310", brand = "Samsung", model = "Galaxy S25", code = "SM-S9310",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-S9310 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s931b", brand = "Samsung", model = "Galaxy S25", code = "SM-S931B",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-S931B Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s931n", brand = "Samsung", model = "Galaxy S25", code = "SM-S931N",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-S931N Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s931q", brand = "Samsung", model = "Galaxy S25", code = "SM-S931Q",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-S931Q Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s931u", brand = "Samsung", model = "Galaxy S25", code = "SM-S931U",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-S931U Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s931u1", brand = "Samsung", model = "Galaxy S25", code = "SM-S931U1",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-S931U1 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s931w", brand = "Samsung", model = "Galaxy S25", code = "SM-S931W",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-S931W Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s931z", brand = "Samsung", model = "Galaxy S25", code = "SM-S931Z",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-S931Z Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s9360", brand = "Samsung", model = "Galaxy S25+", code = "SM-S9360",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-S9360 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s936b", brand = "Samsung", model = "Galaxy S25+", code = "SM-S936B",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-S936B Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s936n", brand = "Samsung", model = "Galaxy S25+", code = "SM-S936N",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-S936N Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s936u", brand = "Samsung", model = "Galaxy S25+", code = "SM-S936U",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-S936U Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s936u1", brand = "Samsung", model = "Galaxy S25+", code = "SM-S936U1",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-S936U1 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s936w", brand = "Samsung", model = "Galaxy S25+", code = "SM-S936W",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-S936W Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s9380", brand = "Samsung", model = "Galaxy S25 Ultra", code = "SM-S9380",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-S9380 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s938b", brand = "Samsung", model = "Galaxy S25 Ultra", code = "SM-S938B",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-S938B Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s938n", brand = "Samsung", model = "Galaxy S25 Ultra", code = "SM-S938N",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-S938N Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s938q", brand = "Samsung", model = "Galaxy S25 Ultra", code = "SM-S938Q",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-S938Q Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s938u", brand = "Samsung", model = "Galaxy S25 Ultra", code = "SM-S938U",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-S938U Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s938u1", brand = "Samsung", model = "Galaxy S25 Ultra", code = "SM-S938U1",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-S938U1 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s938w", brand = "Samsung", model = "Galaxy S25 Ultra", code = "SM-S938W",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-S938W Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-s938z", brand = "Samsung", model = "Galaxy S25 Ultra", code = "SM-S938Z",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SM-S938Z Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "samsung-sm-x700", brand = "Samsung", model = "Galaxy Tab S8", code = "SM-X700",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-X700 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "tablet"
        ),
        Device(
            id = "samsung-sm-x710", brand = "Samsung", model = "Galaxy Tab S9", code = "SM-X710",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-X710 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "tablet"
        ),
        Device(
            id = "samsung-sm-x800", brand = "Samsung", model = "Galaxy Tab S8+", code = "SM-X800",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-X800 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "tablet"
        ),
        Device(
            id = "samsung-sm-x810", brand = "Samsung", model = "Galaxy Tab S9+", code = "SM-X810",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-X810 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "tablet"
        ),
        Device(
            id = "samsung-sm-x820", brand = "Samsung", model = "Galaxy Tab S10+", code = "SM-X820",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-X820 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G720-Immortalis MC12", formFactor = "tablet"
        ),
        Device(
            id = "samsung-sm-x900", brand = "Samsung", model = "Galaxy Tab S8 Ultra", code = "SM-X900",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SM-X900 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "tablet"
        ),
        Device(
            id = "samsung-sm-x910", brand = "Samsung", model = "Galaxy Tab S9 Ultra", code = "SM-X910",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SM-X910 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "tablet"
        ),
        Device(
            id = "samsung-sm-x920", brand = "Samsung", model = "Galaxy Tab S10 Ultra", code = "SM-X920",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-X920 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G720-Immortalis MC12", formFactor = "tablet"
        ),
        Device(
            id = "sony-a201so", brand = "Sony", model = "Xperia 1 IV", code = "A201SO",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; A201SO Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "sony-a202so", brand = "Sony", model = "Xperia 10 IV", code = "A202SO",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; A202SO Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "sony-a204so", brand = "Sony", model = "Xperia 5 IV", code = "A204SO",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; A204SO Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "sony-a301so", brand = "Sony", model = "Xperia 1 V", code = "A301SO",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; A301SO Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "sony-a302so", brand = "Sony", model = "Xperia 10 V", code = "A302SO",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; A302SO Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "sony-a401so", brand = "Sony", model = "Xperia 1 VI", code = "A401SO",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; A401SO Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "sony-a402so", brand = "Sony", model = "Xperia 10 VI", code = "A402SO",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; A402SO Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "sony-a501so", brand = "Sony", model = "Xperia 1 VII", code = "A501SO",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; A501SO Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "sony-a502so", brand = "Sony", model = "Xperia 10 VII", code = "A502SO",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; A502SO Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "sony-so-51c", brand = "Sony", model = "Xperia 1 IV", code = "SO-51C",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SO-51C Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "sony-so-51d", brand = "Sony", model = "Xperia 1 V", code = "SO-51D",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SO-51D Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "sony-so-51e", brand = "Sony", model = "Xperia 1 VI", code = "SO-51E",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SO-51E Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "sony-so-51f", brand = "Sony", model = "Xperia 1 VII", code = "SO-51F",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SO-51F Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "sony-so-52c", brand = "Sony", model = "Xperia 10 IV", code = "SO-52C",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SO-52C Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "sony-so-52d", brand = "Sony", model = "Xperia 10 V", code = "SO-52D",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SO-52D Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "sony-so-52e", brand = "Sony", model = "Xperia 10 VI", code = "SO-52E",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SO-52E Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "sony-so-52f", brand = "Sony", model = "Xperia 10 VII", code = "SO-52F",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SO-52F Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "sony-so-53d", brand = "Sony", model = "Xperia 5 V", code = "SO-53D",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SO-53D Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "sony-so-54c", brand = "Sony", model = "Xperia 5 IV", code = "SO-54C",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SO-54C Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "sony-sog06", brand = "Sony", model = "Xperia 1 IV", code = "SOG06",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SOG06 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "sony-sog07", brand = "Sony", model = "Xperia 10 IV", code = "SOG07",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SOG07 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "sony-sog09", brand = "Sony", model = "Xperia 5 IV", code = "SOG09",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; SOG09 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "sony-sog10", brand = "Sony", model = "Xperia 1 V", code = "SOG10",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SOG10 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "sony-sog11", brand = "Sony", model = "Xperia 10 V", code = "SOG11",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SOG11 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "sony-sog12", brand = "Sony", model = "Xperia 5 V", code = "SOG12",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; SOG12 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "sony-sog13", brand = "Sony", model = "Xperia 1 VI", code = "SOG13",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SOG13 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "sony-sog14", brand = "Sony", model = "Xperia 10 VI", code = "SOG14",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; SOG14 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "sony-sog15", brand = "Sony", model = "Xperia 1 VII", code = "SOG15",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SOG15 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "sony-sog16", brand = "Sony", model = "Xperia 10 VII", code = "SOG16",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; SOG16 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-cc44", brand = "Sony", model = "Xperia 10 IV", code = "XQ-CC44",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; XQ-CC44 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-cc54", brand = "Sony", model = "Xperia 10 IV", code = "XQ-CC54",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; XQ-CC54 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-cc72", brand = "Sony", model = "Xperia 10 IV", code = "XQ-CC72",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; XQ-CC72 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-cq44", brand = "Sony", model = "Xperia 5 IV", code = "XQ-CQ44",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; XQ-CQ44 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-cq54", brand = "Sony", model = "Xperia 5 IV", code = "XQ-CQ54",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; XQ-CQ54 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-cq62", brand = "Sony", model = "Xperia 5 IV", code = "XQ-CQ62",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; XQ-CQ62 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-cq72", brand = "Sony", model = "Xperia 5 IV", code = "XQ-CQ72",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; XQ-CQ72 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-ct44", brand = "Sony", model = "Xperia 1 IV", code = "XQ-CT44",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; XQ-CT44 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-ct54", brand = "Sony", model = "Xperia 1 IV", code = "XQ-CT54",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; XQ-CT54 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-ct62", brand = "Sony", model = "Xperia 1 IV", code = "XQ-CT62",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; XQ-CT62 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-ct72", brand = "Sony", model = "Xperia 1 IV", code = "XQ-CT72",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; XQ-CT72 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-dc44", brand = "Sony", model = "Xperia 10 V", code = "XQ-DC44",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; XQ-DC44 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-dc54", brand = "Sony", model = "Xperia 10 V", code = "XQ-DC54",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; XQ-DC54 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-dc72", brand = "Sony", model = "Xperia 10 V", code = "XQ-DC72",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; XQ-DC72 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-de44", brand = "Sony", model = "Xperia 5 V", code = "XQ-DE44",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; XQ-DE44 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-de54", brand = "Sony", model = "Xperia 5 V", code = "XQ-DE54",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; XQ-DE54 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-de72", brand = "Sony", model = "Xperia 5 V", code = "XQ-DE72",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; XQ-DE72 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-dq44", brand = "Sony", model = "Xperia 1 V", code = "XQ-DQ44",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; XQ-DQ44 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-dq54", brand = "Sony", model = "Xperia 1 V", code = "XQ-DQ54",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; XQ-DQ54 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-dq62", brand = "Sony", model = "Xperia 1 V", code = "XQ-DQ62",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; XQ-DQ62 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-dq72", brand = "Sony", model = "Xperia 1 V", code = "XQ-DQ72",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; XQ-DQ72 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-ec44", brand = "Sony", model = "Xperia 1 VI", code = "XQ-EC44",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; XQ-EC44 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-ec54", brand = "Sony", model = "Xperia 1 VI", code = "XQ-EC54",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; XQ-EC54 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-ec72", brand = "Sony", model = "Xperia 1 VI", code = "XQ-EC72",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; XQ-EC72 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-es44", brand = "Sony", model = "Xperia 10 VI", code = "XQ-ES44",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; XQ-ES44 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-es54", brand = "Sony", model = "Xperia 10 VI", code = "XQ-ES54",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; XQ-ES54 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-es72", brand = "Sony", model = "Xperia 10 VI", code = "XQ-ES72",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; XQ-ES72 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-fe44", brand = "Sony", model = "Xperia 10 VII", code = "XQ-FE44",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; XQ-FE44 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-fe54", brand = "Sony", model = "Xperia 10 VII", code = "XQ-FE54",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; XQ-FE54 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-fe72", brand = "Sony", model = "Xperia 10 VII", code = "XQ-FE72",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; XQ-FE72 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-fs44", brand = "Sony", model = "Xperia 1 VII", code = "XQ-FS44",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; XQ-FS44 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-fs54", brand = "Sony", model = "Xperia 1 VII", code = "XQ-FS54",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; XQ-FS54 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "sony-xq-fs72", brand = "Sony", model = "Xperia 1 VII", code = "XQ-FS72",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; XQ-FS72 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "tcl-6165a", brand = "TCL", model = "TCL 30 SE", code = "6165A",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 6165A Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "tcl-6165h", brand = "TCL", model = "TCL 30 SE", code = "6165H",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 6165H Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "tcl-6165h-eea", brand = "TCL", model = "TCL 30 SE", code = "6165H_EEA",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 6165H_EEA Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "tcl-6165s", brand = "TCL", model = "TCL 30 SE", code = "6165S",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 6165S Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "tcl-9080g", brand = "TCL", model = "TAB 10s 4G 2022", code = "9080G",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 9080G Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "tablet"
        ),
        Device(
            id = "tcl-9081x", brand = "TCL", model = "TAB 10s 2022", code = "9081X",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 9081X Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "tablet"
        ),
        Device(
            id = "tcl-t431a", brand = "TCL", model = "TCL 403", code = "T431A",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; T431A Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "tcl-t431d", brand = "TCL", model = "TCL 403", code = "T431D",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; T431D Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "tcl-t431e", brand = "TCL", model = "TCL 403", code = "T431E",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; T431E Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "tcl-t431p", brand = "TCL", model = "TCL 403", code = "T431P",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; T431P Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "tcl-t431q", brand = "TCL", model = "TCL 403", code = "T431Q",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; T431Q Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "tcl-t431u", brand = "TCL", model = "TCL 403", code = "T431U",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; T431U Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "tcl-t506a", brand = "TCL", model = "TCL 405", code = "T506A",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; T506A Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "tcl-t506d", brand = "TCL", model = "TCL 405", code = "T506D",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; T506D Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "tcl-t507a", brand = "TCL", model = "TCL 408", code = "T507A",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; T507A Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "tcl-t507d", brand = "TCL", model = "TCL 408", code = "T507D",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; T507D Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "tcl-t507f", brand = "TCL", model = "TCL 408", code = "T507F",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; T507F Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "tcl-t507j", brand = "TCL", model = "TCL 408", code = "T507J",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; T507J Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "tcl-t507u", brand = "TCL", model = "TCL 408", code = "T507U",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; T507U Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "tcl-t509a", brand = "TCL", model = "TCL 505", code = "T509A",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; T509A Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "tcl-t509k", brand = "TCL", model = "TCL 505", code = "T509K",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; T509K Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "tcl-t517a", brand = "TCL", model = "TCL 605", code = "T517A",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; T517A Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 613", formFactor = "phone"
        ),
        Device(
            id = "tcl-t517d", brand = "TCL", model = "TCL 605", code = "T517D",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; T517D Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 613", formFactor = "phone"
        ),
        Device(
            id = "tcl-t517f", brand = "TCL", model = "TCL 605", code = "T517F",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; T517F Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 613", formFactor = "phone"
        ),
        Device(
            id = "tcl-t517j", brand = "TCL", model = "TCL 605", code = "T517J",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; T517J Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 613", formFactor = "phone"
        ),
        Device(
            id = "tcl-t608m", brand = "TCL", model = "TCL 40XL", code = "T608M",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; T608M Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "tcl-t610e", brand = "TCL", model = "TCL 40 SE", code = "T610E",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; T610E Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "tcl-t610k", brand = "TCL", model = "TCL 40 SE", code = "T610K",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; T610K Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "tcl-t610p", brand = "TCL", model = "TCL 40 SE", code = "T610P",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; T610P Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "tcl-t611b", brand = "TCL", model = "TCL 50 SE", code = "T611B",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; T611B Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "tcl-t612b", brand = "TCL", model = "TCL 40 NXTPAPER", code = "T612B",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; T612B Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "tcl-t613k", brand = "TCL", model = "TCL 50 5G", code = "T613K",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; T613K Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "tcl-t613p", brand = "TCL", model = "TCL 50 5G", code = "T613P",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; T613P Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "tcl-t671g", brand = "TCL", model = "TCL 30 XL", code = "T671G",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; T671G Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "tcl-t676h", brand = "TCL", model = "TCL 30", code = "T676H",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; T676H Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "tcl-t701dl", brand = "TCL", model = "TCL 30 XL", code = "T701DL",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; T701DL Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "tcl-t776h", brand = "TCL", model = "TCL 30 5G", code = "T776H",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; T776H Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "tcl-t776o", brand = "TCL", model = "TCL 30 5G", code = "T776O",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; T776O Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-bf7n", brand = "TECNO", model = "SPARK Go 2023", code = "TECNO BF7n",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; TECNO BF7n Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-bg6", brand = "TECNO", model = "SPARK Go 2024", code = "TECNO BG6",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; TECNO BG6 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-bg6m", brand = "TECNO", model = "TECNO SPARK Go 2024", code = "TECNO BG6m",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; TECNO BG6m Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-bg6s", brand = "TECNO", model = "TECNO SPARK Go 2024", code = "TECNO BG6s",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; TECNO BG6s Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-bg7", brand = "TECNO", model = "TECNO SPARK 20C", code = "TECNO BG7",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; TECNO BG7 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-bg7n", brand = "TECNO", model = "TECNO SPARK 20C", code = "TECNO BG7n",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; TECNO BG7n Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-ck6", brand = "TECNO", model = "TECNO CAMON 20", code = "TECNO CK6",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; TECNO CK6 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-ck6n", brand = "TECNO", model = "TECNO CAMON 20", code = "TECNO CK6n",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; TECNO CK6n Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-ck6ns", brand = "TECNO", model = "TECNO CAMON 20", code = "TECNO CK6ns",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; TECNO CK6ns Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-ck7n", brand = "TECNO", model = "CAMON 20 Pro", code = "TECNO CK7n",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; TECNO CK7n Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-ck8n", brand = "TECNO", model = "CAMON 20 Pro 5G", code = "TECNO CK8n",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; TECNO CK8n Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G77 MC9", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-ck8nb", brand = "TECNO", model = "TECNO CAMON 20s Pro 5G", code = "TECNO CK8nB",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; TECNO CK8nB Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G77 MC9", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-ck9n", brand = "TECNO", model = "TECNO CAMON 20 Premier 5G", code = "TECNO CK9n",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; TECNO CK9n Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G77 MC9", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-cl6", brand = "TECNO", model = "TECNO CAMON 30", code = "TECNO CL6",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; TECNO CL6 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-cl6k", brand = "TECNO", model = "TECNO CAMON 30", code = "TECNO CL6k",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; TECNO CL6k Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-cl6s", brand = "TECNO", model = "TECNO CAMON 30", code = "TECNO CL6s",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; TECNO CL6s Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-cl7", brand = "TECNO", model = "TECNO CAMON 30 5G", code = "TECNO CL7",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; TECNO CL7 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-cl7k", brand = "TECNO", model = "TECNO CAMON 30 5G", code = "TECNO CL7k",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; TECNO CL7k Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-cl7s", brand = "TECNO", model = "TECNO CAMON 30 5G", code = "TECNO CL7s",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; TECNO CL7s Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-cl8", brand = "TECNO", model = "TECNO CAMON 30 Pro 5G", code = "TECNO CL8",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; TECNO CL8 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G610 MC6", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-cl9", brand = "TECNO", model = "TECNO CAMON 30 Premier 5G", code = "TECNO CL9",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; TECNO CL9 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G610 MC6", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-cla5", brand = "TECNO", model = "TECNO CAMON 30S", code = "TECNO CLA5",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; TECNO CLA5 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-cla6", brand = "TECNO", model = "TECNO CAMON 30S Pro", code = "TECNO CLA6",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; TECNO CLA6 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-kg5", brand = "TECNO", model = "TECNO SPARK Go 2022", code = "TECNO KG5",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; TECNO KG5 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-kg5h", brand = "TECNO", model = "TECNO SPARK Go 2022", code = "TECNO KG5h",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; TECNO KG5h Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-kg5m", brand = "TECNO", model = "TECNO SPARK Go 2022", code = "TECNO KG5m",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; TECNO KG5m Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8320", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-ki5k", brand = "TECNO", model = "TECNO SPARK 10C", code = "TECNO KI5k",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; TECNO KI5k Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-ki5m", brand = "TECNO", model = "TECNO SPARK 10C", code = "TECNO KI5m",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; TECNO KI5m Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-ki5n", brand = "TECNO", model = "TECNO SPARK 10", code = "TECNO KI5n",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; TECNO KI5n Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-ki5q", brand = "TECNO", model = "TECNO SPARK 10", code = "TECNO KI5q",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; TECNO KI5q Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-ki5qs", brand = "TECNO", model = "TECNO SPARK 10", code = "TECNO KI5qs",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; TECNO KI5qs Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-ki7", brand = "TECNO", model = "TECNO SPARK 10 Pro", code = "TECNO KI7",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; TECNO KI7 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-ki7s", brand = "TECNO", model = "TECNO SPARK 10 Pro", code = "TECNO KI7s",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; TECNO KI7s Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-ki8", brand = "TECNO", model = "SPARK 10 5G", code = "TECNO KI8",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; TECNO KI8 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-kj5", brand = "TECNO", model = "TECNO SPARK 20", code = "TECNO KJ5",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; TECNO KJ5 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-kj5n", brand = "TECNO", model = "TECNO SPARK 20", code = "TECNO KJ5n",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; TECNO KJ5n Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-kj5s", brand = "TECNO", model = "TECNO SPARK 20", code = "TECNO KJ5s",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; TECNO KJ5s Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-kj6", brand = "TECNO", model = "TECNO SPARK 20 Pro", code = "TECNO KJ6",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; TECNO KJ6 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-kj7", brand = "TECNO", model = "TECNO SPARK 20 Pro+", code = "TECNO KJ7",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; TECNO KJ7 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-kj7s", brand = "TECNO", model = "TECNO SPARK 20 Pro+", code = "TECNO KJ7s",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; TECNO KJ7s Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-kj8", brand = "TECNO", model = "TECNO SPARK 20 Pro 5G", code = "TECNO KJ8",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; TECNO KJ8 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-kl5", brand = "TECNO", model = "TECNO SPARK 30C", code = "TECNO KL5",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; TECNO KL5 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-kl5n", brand = "TECNO", model = "TECNO SPARK 30C", code = "TECNO KL5n",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; TECNO KL5n Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-kl5s", brand = "TECNO", model = "TECNO SPARK 30C", code = "TECNO KL5s",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; TECNO KL5s Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-kl6", brand = "TECNO", model = "TECNO SPARK 30", code = "TECNO KL6",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; TECNO KL6 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G52 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-kl7", brand = "TECNO", model = "TECNO SPARK 30 Pro", code = "TECNO KL7",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; TECNO KL7 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-kl8", brand = "TECNO", model = "TECNO SPARK 30 5G", code = "TECNO KL8",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; TECNO KL8 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-kl8h", brand = "TECNO", model = "TECNO SPARK 30C 5G", code = "TECNO KL8h",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; TECNO KL8h Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-kl8hs", brand = "TECNO", model = "TECNO SPARK 30C 5G", code = "TECNO KL8hs",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; TECNO KL8hs Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-kl8s", brand = "TECNO", model = "TECNO SPARK 30 5G", code = "TECNO KL8s",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; TECNO KL8s Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "tecno-tecno-mobile-cla6", brand = "TECNO", model = "TECNO CAMON 30T", code = "TECNO Mobile CLA6",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; TECNO Mobile CLA6 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "xiaomi-21051182c", brand = "Xiaomi", model = "Xiaomi Pad 5", code = "21051182C",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 21051182C Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 640", formFactor = "tablet"
        ),
        Device(
            id = "xiaomi-21051182g", brand = "Xiaomi", model = "Xiaomi Pad 5", code = "21051182G",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 21051182G Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 640", formFactor = "tablet"
        ),
        Device(
            id = "xiaomi-2201122c", brand = "Xiaomi", model = "Xiaomi 12 Pro", code = "2201122C",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 2201122C Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "xiaomi-2201122g", brand = "Xiaomi", model = "Xiaomi 12 Pro", code = "2201122G",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 2201122G Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "xiaomi-2201123c", brand = "Xiaomi", model = "Xiaomi 12", code = "2201123C",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 2201123C Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "xiaomi-2201123g", brand = "Xiaomi", model = "Xiaomi 12", code = "2201123G",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 2201123G Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "xiaomi-2203129g", brand = "Xiaomi", model = "Xiaomi 12 Lite", code = "2203129G",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 2203129G Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 642L", formFactor = "phone"
        ),
        Device(
            id = "xiaomi-22071212ag", brand = "Xiaomi", model = "Xiaomi 12T", code = "22071212AG",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 22071212AG Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G610 MC6", formFactor = "phone"
        ),
        Device(
            id = "xiaomi-22081281ac", brand = "Xiaomi", model = "Xiaomi Pad 5 Pro", code = "22081281AC",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; 22081281AC Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 650", formFactor = "tablet"
        ),
        Device(
            id = "xiaomi-2210129sg", brand = "Xiaomi", model = "Xiaomi 13 Lite", code = "2210129SG",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; 2210129SG Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 644", formFactor = "phone"
        ),
        Device(
            id = "xiaomi-2210132g", brand = "Xiaomi", model = "Xiaomi 13 Pro", code = "2210132G",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; 2210132G Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "xiaomi-2211133c", brand = "Xiaomi", model = "Xiaomi 13", code = "2211133C",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; 2211133C Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "xiaomi-2211133g", brand = "Xiaomi", model = "Xiaomi 13", code = "2211133G",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; 2211133G Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "xiaomi-23043rp34c", brand = "Xiaomi", model = "Xiaomi Pad 6", code = "23043RP34C",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; 23043RP34C Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 650", formFactor = "tablet"
        ),
        Device(
            id = "xiaomi-23043rp34g", brand = "Xiaomi", model = "Xiaomi Pad 6", code = "23043RP34G",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; 23043RP34G Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 650", formFactor = "tablet"
        ),
        Device(
            id = "xiaomi-23043rp34i", brand = "Xiaomi", model = "Xiaomi Pad 6", code = "23043RP34I",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; 23043RP34I Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 650", formFactor = "tablet"
        ),
        Device(
            id = "xiaomi-2304fpn6dc", brand = "Xiaomi", model = "Xiaomi 13 Ultra", code = "2304FPN6DC",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; 2304FPN6DC Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "xiaomi-2304fpn6dg", brand = "Xiaomi", model = "Xiaomi 13 Ultra", code = "2304FPN6DG",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; 2304FPN6DG Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "xiaomi-2306epn60g", brand = "Xiaomi", model = "Xiaomi 13T", code = "2306EPN60G",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; 2306EPN60G Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G610 MC6", formFactor = "phone"
        ),
        Device(
            id = "xiaomi-23116pn5bc", brand = "Xiaomi", model = "Xiaomi 14 Pro", code = "23116PN5BC",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 23116PN5BC Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "xiaomi-2311bpn23c", brand = "Xiaomi", model = "Xiaomi 14 Pro", code = "2311BPN23C",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 2311BPN23C Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "xiaomi-23127pn0cc", brand = "Xiaomi", model = "Xiaomi 14", code = "23127PN0CC",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 23127PN0CC Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "xiaomi-23127pn0cg", brand = "Xiaomi", model = "Xiaomi 14", code = "23127PN0CG",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 23127PN0CG Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "xiaomi-24030pn60g", brand = "Xiaomi", model = "Xiaomi 14 Ultra", code = "24030PN60G",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 24030PN60G Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "xiaomi-24031pn0dc", brand = "Xiaomi", model = "Xiaomi 14 Ultra", code = "24031PN0DC",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 24031PN0DC Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "xiaomi-2406apnfag", brand = "Xiaomi", model = "Xiaomi 14T", code = "2406APNFAG",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 2406APNFAG Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC6", formFactor = "phone"
        ),
        Device(
            id = "xiaomi-24091rpadc", brand = "Xiaomi", model = "Xiaomi Pad 7 Pro", code = "24091RPADC",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 24091RPADC Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 735", formFactor = "tablet"
        ),
        Device(
            id = "xiaomi-24091rpadg", brand = "Xiaomi", model = "Xiaomi Pad 7 Pro", code = "24091RPADG",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 24091RPADG Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 735", formFactor = "tablet"
        ),
        Device(
            id = "xiaomi-2410crp4cc", brand = "Xiaomi", model = "Xiaomi Pad 7", code = "2410CRP4CC",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 2410CRP4CC Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 732", formFactor = "tablet"
        ),
        Device(
            id = "xiaomi-2410crp4cg", brand = "Xiaomi", model = "Xiaomi Pad 7", code = "2410CRP4CG",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 2410CRP4CG Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 732", formFactor = "tablet"
        ),
        Device(
            id = "xiaomi-2410crp4ci", brand = "Xiaomi", model = "Xiaomi Pad 7", code = "2410CRP4CI",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; 2410CRP4CI Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 732", formFactor = "tablet"
        ),
        Device(
            id = "xiaomi-2410dpn6cc", brand = "Xiaomi", model = "Xiaomi 15 Pro", code = "2410DPN6CC",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 2410DPN6CC Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "xiaomi-24129pn74c", brand = "Xiaomi", model = "Xiaomi 15", code = "24129PN74C",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 24129PN74C Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "xiaomi-24129pn74g", brand = "Xiaomi", model = "Xiaomi 15", code = "24129PN74G",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 24129PN74G Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "xiaomi-24129pn74i", brand = "Xiaomi", model = "Xiaomi 15", code = "24129PN74I",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 24129PN74I Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "xiaomi-25010pn30c", brand = "Xiaomi", model = "Xiaomi 15 Ultra", code = "25010PN30C",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 25010PN30C Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "xiaomi-25010pn30g", brand = "Xiaomi", model = "Xiaomi 15 Ultra", code = "25010PN30G",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 25010PN30G Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "xiaomi-25010pn30i", brand = "Xiaomi", model = "Xiaomi 15 Ultra", code = "25010PN30I",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 25010PN30I Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "xiaomi-25019pnf3c", brand = "Xiaomi", model = "Xiaomi 15 Ultra", code = "25019PNF3C",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 25019PNF3C Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "xiaomi-25069ptebg", brand = "Xiaomi", model = "Xiaomi 15T", code = "25069PTEBG",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; 25069PTEBG Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G720-Immortalis MC7", formFactor = "phone"
        ),
        Device(
            id = "xiaomi-m2105k81ac", brand = "Xiaomi", model = "Xiaomi Pad 5 Pro", code = "M2105K81AC",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; M2105K81AC Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 650", formFactor = "tablet"
        ),
        Device(
            id = "xiaomi-xig04", brand = "Xiaomi", model = "Xiaomi 13T", code = "XIG04",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; XIG04 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G610 MC6", formFactor = "phone"
        ),
        Device(
            id = "xiaomi-xig07", brand = "Xiaomi", model = "Xiaomi 14T", code = "XIG07",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; XIG07 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC6", formFactor = "phone"
        ),
        Device(
            id = "xiaomi-xiaomi-for-arm64", brand = "Xiaomi", model = "Xiaomi 14 Ultra", code = "Xiaomi for arm64",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; Xiaomi for arm64 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "zte-zte-a2022l", brand = "ZTE", model = "ZTE A2022L", code = "ZTE A2022L",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; ZTE A2022L Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MP1", formFactor = "phone"
        ),
        Device(
            id = "zte-zte-a2022pg", brand = "ZTE", model = "ZTE A2022PG", code = "ZTE A2022PG",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; ZTE A2022PG Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MP1", formFactor = "phone"
        ),
        Device(
            id = "zte-zte-a2023", brand = "ZTE", model = "ZTE A2023", code = "ZTE A2023",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; ZTE A2023 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MP1", formFactor = "phone"
        ),
        Device(
            id = "zte-zte-a2023g", brand = "ZTE", model = "ZTE A2023G", code = "ZTE A2023G",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; ZTE A2023G Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MP1", formFactor = "phone"
        ),
        Device(
            id = "zte-zte-a2023p", brand = "ZTE", model = "ZTE A2023P", code = "ZTE A2023P",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; ZTE A2023P Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MP1", formFactor = "phone"
        ),
        Device(
            id = "zte-zte-a2023pg", brand = "ZTE", model = "ZTE A2023PG", code = "ZTE A2023PG",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; ZTE A2023PG Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MP1", formFactor = "phone"
        ),
        Device(
            id = "iqoo-i2306", brand = "iQOO", model = "iQOO Z9 Lite", code = "I2306",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; I2306 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "iqoo-i2405", brand = "iQOO", model = "iQOO Neo 10", code = "I2405",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; I2405 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 825", formFactor = "phone"
        ),
        Device(
            id = "iqoo-i2408", brand = "iQOO", model = "iQOO Neo 10", code = "I2408",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; I2408 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 825", formFactor = "phone"
        ),
        Device(
            id = "iqoo-i2409", brand = "iQOO", model = "iQOO Z10 Lite 5G", code = "I2409",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; I2409 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "iqoo-i2501", brand = "iQOO", model = "iQOO 15", code = "I2501",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; I2501 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 840", formFactor = "phone"
        ),
        Device(
            id = "iqoo-i2502", brand = "iQOO", model = "iQOO Z10 Lite", code = "I2502",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; I2502 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "iqoo-i2505", brand = "iQOO", model = "iQOO Z10R 5G", code = "I2505",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; I2505 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "iqoo-i2507", brand = "iQOO", model = "iQOO Z11x 5G", code = "I2507",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; I2507 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "iqoo-i2508", brand = "iQOO", model = "iQOO 15R", code = "I2508",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; I2508 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 825", formFactor = "phone"
        ),
        Device(
            id = "iqoo-i2512", brand = "iQOO", model = "iQOO Z11 5G", code = "I2512",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; I2512 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "iqoo-v2452a", brand = "iQOO", model = "iQOO Z10 Turbo", code = "V2452A",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; V2452A Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G720-Immortalis MC7", formFactor = "phone"
        ),
        Device(
            id = "iqoo-v2505a", brand = "iQOO", model = "iQOO 15", code = "V2505A",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; V2505A Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 840", formFactor = "phone"
        ),
        Device(
            id = "iqoo-v2520a", brand = "iQOO", model = "iQOO Neo11", code = "V2520A",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; V2520A Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 825", formFactor = "phone"
        ),
        Device(
            id = "iqoo-v2532a", brand = "iQOO", model = "iQOO Z11x", code = "V2532A",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; V2532A Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "iqoo-v2536a", brand = "iQOO", model = "iQOO Z11 Turbo", code = "V2536A",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; V2536A Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G720-Immortalis MC7", formFactor = "phone"
        ),
        Device(
            id = "iqoo-v2546a", brand = "iQOO", model = "iQOO 15 Ultra", code = "V2546A",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; V2546A Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 840", formFactor = "phone"
        ),
        Device(
            id = "iqoo-v2551a", brand = "iQOO", model = "iQOO Z11", code = "V2551A",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; V2551A Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "iqoo-v2559ua", brand = "iQOO", model = "iQOO Z11i", code = "V2559UA",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; V2559UA Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "iqoo-v2564a", brand = "iQOO", model = "iQOO 15T", code = "V2564A",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; V2564A Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "itel-itel-a507lc", brand = "itel", model = "itel A24 2023", code = "itel A507LC",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; itel A507LC Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8322", formFactor = "phone"
        ),
        Device(
            id = "itel-itel-a507lnp", brand = "itel", model = "itel A24 2023", code = "itel A507LNP",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; itel A507LNP Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8322", formFactor = "phone"
        ),
        Device(
            id = "itel-itel-a507lsp", brand = "itel", model = "itel A24 2023", code = "itel A507LSP",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; itel A507LSP Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8322", formFactor = "phone"
        ),
        Device(
            id = "itel-itel-a507lx", brand = "itel", model = "itel  A24 2023", code = "itel A507LX",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; itel A507LX Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Imagination Technologies", gpuRenderer = "PowerVR Rogue GE8322", formFactor = "phone"
        ),
        Device(
            id = "realme-rmx3301", brand = "realme", model = "realme GT 2 Pro", code = "RMX3301",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; RMX3301 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "realme-rmx3312", brand = "realme", model = "realme GT 2", code = "RMX3312",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; RMX3312 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "realme-rmx3615", brand = "realme", model = "realme 10", code = "RMX3615",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; RMX3615 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "realme-rmx3630", brand = "realme", model = "realme 10", code = "RMX3630",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; RMX3630 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "realme-rmx3636", brand = "realme", model = "realme 11", code = "RMX3636",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; RMX3636 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "realme-rmx3663", brand = "realme", model = "realme 10 Pro", code = "RMX3663",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; RMX3663 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "realme-rmx3687", brand = "realme", model = "realme 10 Pro+", code = "RMX3687",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; RMX3687 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "realme-rmx3740", brand = "realme", model = "realme 11 Pro+", code = "RMX3740",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; RMX3740 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "realme-rmx3751", brand = "realme", model = "realme 11", code = "RMX3751",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; RMX3751 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "realme-rmx3820", brand = "realme", model = "realme GT5", code = "RMX3820",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; RMX3820 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "realme-rmx3851", brand = "realme", model = "realme GT 6", code = "RMX3851",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; RMX3851 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 735", formFactor = "phone"
        ),
        Device(
            id = "realme-rmx3853", brand = "realme", model = "realme GT 6T", code = "RMX3853",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; RMX3853 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 732", formFactor = "phone"
        ),
        Device(
            id = "realme-rmx3868", brand = "realme", model = "NARZO 70 Pro 5G", code = "RMX3868",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; RMX3868 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G68 MC4", formFactor = "phone"
        ),
        Device(
            id = "realme-rmx3869", brand = "realme", model = "NARZO 70 5G", code = "RMX3869",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; RMX3869 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "realme-rmx3871", brand = "realme", model = "realme 12", code = "RMX3871",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; RMX3871 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "realme-rmx3888", brand = "realme", model = "realme GT5 Pro", code = "RMX3888",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; RMX3888 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 750", formFactor = "phone"
        ),
        Device(
            id = "realme-rmx5003", brand = "realme", model = "realme NARZO 70 Turbo 5G", code = "RMX5003",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; RMX5003 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "realme-rmx5011", brand = "realme", model = "realme GT 7 Pro", code = "RMX5011",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; RMX5011 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 830", formFactor = "phone"
        ),
        Device(
            id = "realme-rmx5033", brand = "realme", model = "realme NARZO 80 Pro 5G", code = "RMX5033",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; RMX5033 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G615 MC2", formFactor = "phone"
        ),
        Device(
            id = "realme-rmx5085", brand = "realme", model = "realme GT 7T", code = "RMX5085",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; RMX5085 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G720-Immortalis MC7", formFactor = "phone"
        ),
        Device(
            id = "vivo-v2022", brand = "vivo", model = "V2022", code = "V2022",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; V2022 Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 610", formFactor = "phone"
        ),
        Device(
            id = "vivo-v2023", brand = "vivo", model = "V2023", code = "V2023",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; V2023 Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "vivo-v2023a", brand = "vivo", model = "V2023A", code = "V2023A",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; V2023A Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "vivo-v2023ea", brand = "vivo", model = "V2023EA", code = "V2023EA",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; V2023EA Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "vivo-v2024", brand = "vivo", model = "V2024", code = "V2024",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; V2024 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "vivo-v2024a", brand = "vivo", model = "V2024A", code = "V2024A",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; V2024A Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 619", formFactor = "phone"
        ),
        Device(
            id = "vivo-v2025", brand = "vivo", model = "V2025", code = "V2025",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; V2025 Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "vivo-v2025a", brand = "vivo", model = "V2025A", code = "V2025A",
            year = 2025, androidVersion = "15", chromeVersion = "156.0.8078.48", buildId = "AP3A.241105.007",
            userAgent = "Mozilla/5.0 (Linux; Android 15; V2025A Build/AP3A.241105.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G57 MC2", formFactor = "phone"
        ),
        Device(
            id = "vivo-v2183a", brand = "vivo", model = "vivo X80", code = "V2183A",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; V2183A Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G710 MC10", formFactor = "phone"
        ),
        Device(
            id = "vivo-v2185a", brand = "vivo", model = "vivo X80 Pro", code = "V2185A",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; V2185A Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 730", formFactor = "phone"
        ),
        Device(
            id = "vivo-v2190a", brand = "vivo", model = "vivo S15e", code = "V2190A",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; V2190A Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G78 MP10", formFactor = "phone"
        ),
        Device(
            id = "vivo-v2199ga", brand = "vivo", model = "vivo T2", code = "V2199GA",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; V2199GA Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 710", formFactor = "phone"
        ),
        Device(
            id = "vivo-v2207a", brand = "vivo", model = "vivo S15 Pro", code = "V2207A",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; V2207A Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G610 MC6", formFactor = "phone"
        ),
        Device(
            id = "vivo-v2227a", brand = "vivo", model = "vivo X90 Pro+", code = "V2227A",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; V2227A Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 740", formFactor = "phone"
        ),
        Device(
            id = "vivo-v2239a", brand = "vivo", model = "vivo S16e", code = "V2239A",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; V2239A Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G78 MP10", formFactor = "phone"
        ),
        Device(
            id = "vivo-v2241a", brand = "vivo", model = "vivo X90", code = "V2241A",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; V2241A Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G715-Immortalis MC11", formFactor = "phone"
        ),
        Device(
            id = "vivo-v2242a", brand = "vivo", model = "vivo X90 Pro", code = "V2242A",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; V2242A Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G715-Immortalis MC11", formFactor = "phone"
        ),
        Device(
            id = "vivo-v2244a", brand = "vivo", model = "vivo S16", code = "V2244A",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; V2244A Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 720", formFactor = "phone"
        ),
        Device(
            id = "vivo-v2245a", brand = "vivo", model = "vivo S16 Pro", code = "V2245A",
            year = 2022, androidVersion = "12", chromeVersion = "156.0.8078.48", buildId = "SP1A.210812.016",
            userAgent = "Mozilla/5.0 (Linux; Android 12; V2245A Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 4, hardwareConcurrency = 6,
            gpuVendor = "ARM", gpuRenderer = "Mali-G610 MC6", formFactor = "phone"
        ),
        Device(
            id = "vivo-v2285a", brand = "vivo", model = "vivo S17e", code = "V2285A",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; V2285A Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G610 MC4", formFactor = "phone"
        ),
        Device(
            id = "vivo-v2323a", brand = "vivo", model = "vivo S18", code = "V2323A",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; V2323A Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 720", formFactor = "phone"
        ),
        Device(
            id = "vivo-v2334a", brand = "vivo", model = "vivo S18e", code = "V2334A",
            year = 2023, androidVersion = "13", chromeVersion = "156.0.8078.48", buildId = "TP1A.220624.014",
            userAgent = "Mozilla/5.0 (Linux; Android 13; V2334A Build/TP1A.220624.014) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G610 MC4", formFactor = "phone"
        ),
        Device(
            id = "vivo-v2364a", brand = "vivo", model = "vivo S19", code = "V2364A",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; V2364A Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "Qualcomm", gpuRenderer = "Adreno (TM) 720", formFactor = "phone"
        ),
        Device(
            id = "vivo-v2415", brand = "vivo", model = "vivo X200", code = "V2415",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; V2415 Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G925-Immortalis MC12", formFactor = "phone"
        ),
        Device(
            id = "vivo-v2415a", brand = "vivo", model = "vivo X200", code = "V2415A",
            year = 2024, androidVersion = "14", chromeVersion = "156.0.8078.48", buildId = "UP1A.231005.007",
            userAgent = "Mozilla/5.0 (Linux; Android 14; V2415A Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36",
            deviceMemoryGb = 8, hardwareConcurrency = 8,
            gpuVendor = "ARM", gpuRenderer = "Mali-G925-Immortalis MC12", formFactor = "phone"
        ),
    )

    val byId: Map<String, Device> = all.associateBy { it.id }

    fun find(id: String?): Device? = id?.let { byId[it] }

    /**
     * A device no other profile is using. [taken] is the set of ids
     * already assigned to the registry; every profile gets a different
     * handset until the catalogue runs out, at which point the pool is
     * the whole catalogue again (one shared device beats no assignment).
     */
    fun random(taken: Set<String> = emptySet()): Device {
        val free = all.filterNot { it.id in taken }
        return (if (free.isEmpty()) all else free).random()
    }
}
