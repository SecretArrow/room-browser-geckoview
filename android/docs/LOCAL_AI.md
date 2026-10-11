# Local AI (Ollama) — Model AI di Perangkat Sendiri

Room Browser punya menu **Local AI (Ollama)**: instal, kelola, dan
ekspor-impor model AI yang berjalan **sepenuhnya di jaringan lokal Anda** —
di ponsel yang sama (lewat Termux) atau di PC di LAN. Tidak ada cloud, tidak
ada API key, tidak ada data yang keluar dari jaringan rumah/kantor Anda.
Sejak task 13 ada juga **mesin on-device tertanam** (llama.cpp di dalam
aplikasi, tanpa server sama sekali) — lihat [Mesin on-device (tanpa
Termux)](#mesin-on-device-tanpa-termux) di bawah.

Menu ini diakses dari **AI Agent Settings → Local AI (Ollama)**, atau lewat
link **Manage local models →** saat menambah/mengedit provider
**Ollama native**.

## Mesin on-device (tanpa Termux)

Sejak task 13, layar Local AI punya bagian **On-device engine (built-in, no
Termux)** — mesin inferensi **llama.cpp yang tertanam langsung di dalam
aplikasi**. Tidak ada server, tidak ada Termux, tidak ada jaringan sama
sekali: inferensi berjalan **di CPU ponsel ini**, di dalam proses app.

Kejujuran teknisnya:

- **CPU-only.** Mesin ini berjalan di CPU (runtime ggml-CPU) — jujur soal
  kecepatan: model kecil (≤1B) realistis untuk browsing sederhana; model
  besar butuh kesabaran. Kecepatannya nyata, tapi terbatas.
- **Modelnya file `.gguf` biasa** di penyimpanan privat app
  (`noBackupFilesDir/on_device_models`). Tidak ada format proprietary,
  tidak ada bobot yang dibundel di APK — Anda **impor / ekspor / unduh
  sendiri**:
  - **Import .gguf file** — pilih file apa pun lewat SAF (file manager,
    cloud drive, flashdisk OTG).
  - **Export** per model — tulis ulang file `.gguf` ke lokasi mana pun
    (SAF) untuk dipindah ke perangkat lain atau di-backup.
  - **Download URL** — unduh langsung dari URL `.gguf` (mis. dari
    Hugging Face) dengan **pause/resume berbasis HTTP Range** (file
    `.part` dilanjutkan, bukan diunduh ulang).
- **Try model** — tombol diagnostik per model: memuat model ke engine dan
  menjalankan satu generasi pendek ("Once upon a time", 24 token). Hasilnya
  (atau pesan error yang jujur) tampil di dialog — cara tercepat
  membuktikan stack natif bekerja di perangkat Anda.
- **Protokol provider "On-device"** — tombol **Use in chat** per model
  membuat/memperbarui provider berprotokol `LOCAL` (base URL palsu
  `local://engine`, tanpa API key). Gateway
  `LocalLlamaGateway` meresolve model dari direktori yang sama — chat agent
  berjalan penuh di perangkat. Di editor provider ada chip keempat
  **On-device**; tombol **Fetch models** di sana membaca daftar `.gguf`
  dari penyimpanan lokal (bukan HTTP).
- **Atribusi:** didukung oleh **llama.cpp** (lisensi MIT) — salinan
  upstream di-vendor di `app/src/main/cpp/llamacpp`, berasal dari
  <https://github.com/ggml-org/llama.cpp>. Hanya backend CPU yang aktif
  (backend GPU dihapus dari vendor copy).

## Apa itu Local AI (Ollama)?

[Ollama](https://ollama.com) adalah server model AI lokal yang menjalankan
model open-source (Llama, Qwen, Gemma, DeepSeek…) langsung di perangkat Anda.
Untuk jalur Ollama ini Room Browser **tidak menjalankan modelnya sendiri** —
Room Browser adalah **klien manajemen** untuk server Ollama Anda (jalur
tanpa server ada di bagian [Mesin on-device](#mesin-on-device-tanpa-termux)):

- terhubung ke server (cek versi, status online/offline),
- menampilkan model yang sudah terpasang di server,
- mengunduh model baru lewat katalog kurasi (dengan pause/resume),
- menyetel performa (GPU layers, thread CPU, context window, keep-alive),
- mengekspor/mengimpor *setup* sebagai file JSON kecil.

Setelah model terpasang, tombol **Use in chat** menjadikannya model default
agent browsing Anda — semua percakapan agent berjalan di server lokal.

## Arsitektur (jujur)

```
┌──────────────┐   HTTP (LAN / localhost)   ┌─────────────────────┐
│ Room Browser │ ─────────────────────────→ │ Server Ollama       │
│ (klien       │   /api/version  /api/tags  │ • Termux di ponsel  │
│ manajemen)   │   /api/pull     /api/delete│   ini, ATAU         │
└──────────────┘                            │ • PC di LAN         │
                                            └─────────────────────┘
```

Kejujuran teknis yang penting:

- **Model TIDAK dibundel di APK.** File model itu ratusan MB sampai
  multi-GB — diunduh **ke server Ollama**, bukan ke aplikasi. Tetapi APK
  kini **tidak lagi** sekecil versi lama (~6 MB): sejak mesin on-device
  (llama.cpp) ditanamkan (lihat [Mesin on-device](#mesin-on-device-tanpa-termux)),
  pustaka natifnya dikompilasi **per-ABI** dan ikut dikemas di tiap APK
  split, jadi ukurannya bertambah sesuai ABI dan konfigurasi build.
  Berapa tepatnya tidak dipatok di sini — yang pasti, bukan lagi ~6 MB,
  dan bobot model tetap tidak ada di dalamnya.
- Room Browser **hanya klien manajemen**: tanpa server Ollama yang
  berjalan, menu ini hanya menampilkan status offline.
- **Pause** menghentikan tampilan unduhan di aplikasi ini; server Ollama
  **menyimpan layer yang sudah terunduh** di blob cache-nya, dan
  **Resume** menerbitkan ulang `POST /api/pull` yang melewati layer yang
  sudah selesai — jadi resume tidak pernah mengunduh ulang layer yang
  sudah ada.
- Tuning GPU benar-benar dikirim ke server (`num_gpu`, `num_thread`,
  `num_ctx`, `keep_alive`) lewat protokol **Ollama natif `/api/chat`** —
  bukan dekoratif.

## Setup — Termux di ponsel ini (cara utama)

1. **Instal Termux** dari F-Droid atau rilis GitHub Termux (versi
   Play Store sudah lama tidak diperbarui — hindari).
2. Buka Termux, lalu:

   ```bash
   pkg update
   pkg install ollama
   ```

   `pkg install ollama` tersedia di repo Termux modern. Jika tidak
   tersedia di repositori Anda, gunakan build komunitas Android Ollama
   (ikuti dokumentasi proyeknya) — Room Browser tidak peduli build mana,
   yang penting ada server yang bicara di `http://localhost:11434`.
3. Jalankan servernya:

   ```bash
   ollama serve
   ```

   Biarkan Termux tetap berjalan (matikan optimasi baterai untuk Termux
   agar Android tidak membunuh prosesnya).
4. Buka Room Browser → **AI Agent Settings → Local AI (Ollama)** →
   alamat sudah benar secara default (`http://localhost:11434`) →
   ketuk **Connect**.
5. Pilih model dari katalog di bawah → **Install**. Model diunduh
   langsung ke server Termux (gunakan Wi-Fi — ukurannya ratusan MB
   sampai GB).

## Setup — Ollama di PC pada LAN

1. Instal Ollama di PC (Windows/macOS/Linux) dari ollama.com.
2. Jadikan servernya terjangkau dari LAN:

   ```bash
   # Linux/macOS
   OLLAMA_HOST=0.0.0.0 ollama serve

   # Windows (PowerShell)
   $env:OLLAMA_HOST="0.0.0.0"; ollama serve
   ```

3. Izinkan port **11434** lewat firewall PC ( inbound TCP).
4. Di Room Browser → Local AI → isi alamat
   `http://<ip-pc-anda>:11434` (contoh: `http://192.168.1.10:11434`)
   → **Connect**.

**Keamanan:** server Ollama di LAN tidak punya autentikasi bawaan —
pastikan PC/ponsel Anda hanya di jaringan tepercaya (Wi-Fi rumah),
jangan pernah mem-forward port 11434 ke internet. Segmen jaringan
`usesCleartextTraffic` di aplikasi ini diperlukan karena server LAN
berbicara HTTP polos.

## Menu Local AI

### Koneksi

Field **Ollama server address** (default `http://localhost:11434` untuk
Termux di ponsel yang sama; ganti ke IP PC untuk setup LAN) + tombol
**Connect** yang mengecek `GET /api/version` dan menampilkan status:
`Connected · Ollama <versi>` atau alasan gagal. Kartu **Setup guide**
bisa dibuka untuk langkah-langkah singkat di atas.

### Model terpasang (Installed models)

Daftar dari `GET /api/tags`: nama model (`repo:tag`), ukuran on-disk,
family, jumlah parameter, dan kuantisasi. Tiap baris punya:

- **Use in chat** — menjadikan model ini model default agent (membuat /
  memilih provider "Local Ollama" berprotokol natif, menunjuk host yang
   terpasang saat itu);
- **Delete** — menghapus model dari server (dengan dialog konfirmasi;
  `DELETE /api/delete`).

### Katalog model — terbaik untuk ponsel

19 preset kurasi, dikelompokkan dalam 4 tier berdasarkan RAM, plus saran
tier otomatis sesuai RAM perangkat Anda. Ukuran = perkiraan unduhan tag
q4 default (kuantisasi 4-bit standar).

| Tag | Parameter | Unduhan | RAM min | Konteks | Kelebihan | Tier |
|---|---|---|---|---|---|---|
| `smollm2:360m` | 0.4B | ~269 MB | 3 GB | 4096 | Chat koheren meski sangat kecil | Ultra light |
| `gemma3:270m` | 0.3B | ~313 MB | 3 GB | 32768 | Gemma 3 terkecil — kuat untuk 270M | Ultra light |
| `qwen2.5:0.5b` | 0.5B | ~397 MB | 3 GB | 32768 | Pemula tercepat, multibahasa lumayan | Ultra light |
| `qwen3:0.6b` | 0.6B | ~522 MB | 3 GB | 32768 | Qwen terbaru terkecil — thinking mode | Ultra light |
| `tinyllama` | 1.1B | ~608 MB | 3 GB | 2048 | Model mini klasik — sangat cepat, kualitas dasar | Ultra light |
| `gemma3:1b` ⭐ | 1.0B | ~815 MB | 3 GB | 32768 | Model terkecil Google — tak lazim kuat untuk ukurannya | Ultra light (rekomendasi) |
| `qwen2.5:1.5b` | 1.5B | ~986 MB | 4 GB | 32768 | Balance kualitas/kecepatan yang teruji | Light |
| `qwen3:1.7b` ⭐ | 1.7B | ~1100 MB | 4 GB | 32768 | Qwen kompak terbaru — thinking mode, model kecil baru terbaik | Light (rekomendasi) |
| `deepseek-r1:1.5b` | 1.5B | ~1113 MB | 4 GB | 32768 | Model reasoning mini dengan chain-of-thought terlihat | Light |
| `llama3.2:1b` | 1.0B | ~1328 MB | 4 GB | 131072 | Model phone-first Meta, multibahasa bagus | Light |
| `gemma2:2b` | 2.6B | ~1612 MB | 4 GB | 8192 | Model Google seimbang, prosa bersih | Light |
| `qwen2.5:3b` ⭐ | 3.1B | ~1900 MB | 6 GB | 32768 | Kualitas terbaik yang realistis di ponsel; tool calling kuat | Balanced (rekomendasi) |
| `llama3.2:3b` | 3.2B | ~2010 MB | 6 GB | 131072 | Llama 3.2 lebih besar — multibahasa terbaik | Balanced |
| `phi3.5` | 3.8B | ~2163 MB | 6 GB | 131072 | Model efisien Microsoft dengan konteks panjang | Balanced |
| `qwen3:4b` | 4.0B | ~2600 MB | 6 GB | 32768 | Qwen mid-size terbaru, reasoning kuat | Balanced |
| `gemma3:4b` | 4.3B | ~3336 MB | 6 GB | 32768 | Gemma 3 dengan vision — kualitas naik di 4B | Balanced |
| `deepseek-r1:7b` | 7.6B | ~4689 MB | 8 GB | 32768 | Model reasoning penuh dengan chain-of-thought terlihat | Heavy |
| `qwen2.5:7b` | 7.1B | ~4720 MB | 8 GB | 32768 | Kualitas flagship — butuh ponsel top + kesabaran | Heavy |
| `llama3.1:8b` ⭐ | 8.0B | ~4930 MB | 8 GB | 131072 | Kualitas asisten penuh di ponsel flagship | Heavy (rekomendasi) |

⭐ = pilihan kurator tier (badge "Recommended" di UI). Sebagian besar
preset juga ber-badge **ID-friendly** (cocok untuk tugas browsing
berbahasa Indonesia — kekuatan bahasa Indonesia model kecil bervariasi).

**Saran RAM konservatif:** Android sendiri makan ~2 GB — ponsel "6 GB"
berperilaku seperti 4 GB saat LLM dan browser berjalan bersamaan.
Saran tier: RAM < 4 GB → Ultra light; < 6 GB → Light; < 8 GB →
Balanced; ≥ 8 GB → Heavy.

### Find new models — refresh katalog live

Preset di atas dibekukan saat rilis. Tombol **Find new models** di atas
tier katalog mengambil **library publik ollama.com secara live**
(`GET https://ollama.com/library?sort=newest`, hanya-baca, HTTPS) lalu
menampilkan famili model **baru** yang belum tercakup preset dan cocok
untuk ponsel:

- **Badge ukuran parameter** (1.5b, 270m, …) di listing ollama.com adalah
  **tag yang bisa langsung di-pull** — badge ≤ 4B jadi tombol **Install**
  dengan perkiraan unduhan q4 (ber-label *est.*; progres pull menampilkan
  byte nyata).
- **Badge > 4B** tampil sebagai info "too big for phones" (tidak bisa
  di-install dari sini).
- Famili **embedding-only** (bge-m3, nomic-embed…) disembunyikan — tidak
  bisa chat.
- Kartu memuat deskripsi, badge kapabilitas (tools/thinking/vision), dan
  label "updated X ago" dari listing.
- Maksimum 20 kartu ditampilkan (urutan terbaru dulu) + ringkasan jumlah
  sisanya.

Gagal fetch (offline, situs berubah markup) = pesan error jujur + preset
kurasi tetap berfungsi penuh — refresh tidak pernah merusak katalog.
Parser-nya unit-test terhadap fixture markup asli; e2e membuktikan alur
penuh tombol → fetch → kartu → install via MockWebServer.

Ukuran unduhan model yang ditemukan dihitung dengan heuristik
`150 MB + 650 MB × parameter (miliar)` — cukup dekat dengan q4 nyata
(0.8B → ~670 MB; 4B → ~2.7 GB) dan selalu ber-label *est.*

### Downloads — pause/resume

Saat menekan **Install**, baris unduhan muncul dengan progres per-layer
(NDJSON `POST /api/pull`), tombol **Pause / Resume / Cancel**, dan
baris **Clear** setelah sukses.

Kejujuran tentang pause/resume:

- **Pause** membatalkan coroutine unduhan **di aplikasi ini** — server
  Ollama tetap menyimpan layer yang sudah selesai di blob cache-nya.
- **Resume** menerbitkan ulang pull yang sama — server melewati layer
  yang sudah tersimpan, jadi lanjut dari layer terakhir yang selesai.
  API Ollama memang tidak punya HTTP Range resume; re-issue inilah
  mekanisme resume-nya.
- `receivedBytes` adalah tanda air tinggi (high-water mark) yang bertahan
  melewati pause, jadi progres di UI tidak pernah mundur setelah resume.

### Import / Export setup

**Export setup** menulis file JSON kecil (SAF) berisi: alamat server,
tuning performa, dan daftar nama model — **bukan** file model multi-GB.
**Import setup** membaca manifest itu, memulihkan host + tuning, dan
mengantri ulang pull untuk model yang belum terpasang. Cocok untuk
pindah-pindah perangkat atau instal ulang Termux tanpa mengetik ulang.

### Performance (GPU · CPU)

- **GPU layers to offload** (`num_gpu`) — Auto (server menentukan) atau
  manual 0–99.
- **CPU threads** (`num_thread`) — Auto atau manual 1–16.
- **Context window** (`num_ctx`) — 512–16384, kelipatan 512. Catatan:
  llama.cpp **mengalokasikan RAM untuk SELURUH window di muka** —
  konteks 16k pada model 3B ≈ +1 GB RAM.
- **Keep model in memory** (`keep_alive`) — 0–60 menit; 0 berarti model
  langsung dibongkar dari RAM setelah balasan terakhir.

Catatan jujur GPU: offload GPU mempercepat model **hanya jika build
Ollama Anda mendukung GPU ponsel** (mis. build Termux dengan
OpenCL/Adreno). Build standar mengabaikan `num_gpu` dan otomatis jatuh
ke CPU — tidak ada yang rusak, hanya tidak ada percepatan. Nilai-nilai
ini diterapkan ke chat **provider Ollama natif**.

## Provider: Ollama natif vs /v1

| | **Ollama native** | **Ollama (OpenAI /v1)** |
|---|---|---|
| Protokol | `/api/chat` Ollama asli | endpoint kompatibel OpenAI `/v1` |
| Tuning Local AI (GPU/thread/konteks/keep-alive) | ✅ diterapkan ke setiap chat | ❌ tidak diterapkan |
| Daftar model | model terpasang di server (`/api/tags`) | `/v1/models` |
| Kapan dipakai | ingin tuning performa + integrasi menu Local AI | provider standar OpenAI-compatible (paling kompatibel lintas aplikasi) |

Di **Add AI provider**: pilih chip **Ollama native** (preset
`http://localhost:11434`, protokol OLLAMA) atau preset **Ollama
(OpenAI /v1)** (`http://localhost:11434/v1`, protokol OPENAI). Tombol
**Use in chat** di menu Local AI selalu memakai jalur natif.

## Privasi

- Semua trafik menuju **server lokal Anda** (localhost atau LAN) —
  tidak ada cloud, tidak ada proxy, tidak ada telemetry dari Room
  Browser (lihat `PRIVACY.md`).
- Tidak ada API key yang perlu disimpan untuk server lokal.
- File export berisi host + tuning + nama model saja — tidak berisi
  percakapan agent Anda.
- Model berjalan di perangkat Anda: halaman yang dibaca agent tidak
  dikirim ke pihak ketiga mana pun.

## Batasan (jujur, tanpa klaim palsu)

- **Unduhan besar** — model 1–5 GB: gunakan Wi-Fi; unduhan berhenti di
  layer terakhir yang selesai bila koneksi putus (resume melanjutkan).
- **Pause = tampilan klien + cache layer server** — bukan pause server
  yang sebenarnya (API Ollama tidak punya Range resume; re-issue pull
  adalah satu-satunya mekanisme, dan itu cukup efisien karena blob
  cache).
- **GPU opsional per build** — hanya build Ollama dengan dukungan GPU
  ponsel yang memakai `num_gpu`; build lain otomatis CPU.
- **Kecepatan model kecil di ponsel itu nyata tapi terbatas** — model
  1B–3B realistis untuk tugas browsing sederhana; model 7B+ butuh
  ponsel flagship, pendinginan, dan kesabaran.
- Server Ollama harus **tetap berjalan** (Termux/PC) — kalau mati, agent
  lokal tidak bisa menjawab.

## Arsitektur kode

```
core:domain (JVM murni, teruji unit)
├── OllamaDtos      DTO /api/tags + parser NDJSON /api/pull (lenient)
├── OllamaModelPresets  katalog 13 preset 4 tier + saran tier per RAM
├── LocalAiTuning   num_gpu/num_thread/num_ctx/keep_alive + clamp
├── LocalAiBackup   manifest import/export (encode/decode ketat)
└── localai/Gguf    parser header GGUF v1-v3 → GgufMeta (nama, kuantisasi,
                   arsitektur, ctx, jumlah parameter approx)

app (proses default — tanpa WebView)
├── OllamaClient    GET /api/version, /api/tags, POST /api/pull (NDJSON
│                  streaming, cancel = pause), DELETE /api/delete
├── LocalAiController  state machine: koneksi, installed, downloads
│                  (pause = cancel job; resume = re-issue pull),
│                  tuning, use-in-chat, import/export
├── localai/engine/LlamaEngine   pembungkus coroutine di atas JNI llama.cpp
│                  (load/unload/completeRaw/chat + StateFlow status)
├── localai/store/OnDeviceModelStore       daftar/hapus/impor/ekspor .gguf
├── localai/store/OnDeviceDownloadController  unduhan URL .gguf + Range resume
└── agent/ui/LocalAiActivity  layar Compose (activity sendiri)

app (proses :browser)
├── OllamaAgentGateway  protokol natif /api/chat — mengirim options
│                      tuning Local AI pada setiap chat agent
└── LocalLlamaGateway   protokol "LOCAL" — turn agent penuh on-device
                        (resolve .gguf, load sekali, satu Text event)
```

## Test

- **Domain (JVM)**: `OllamaDtosTest` — parsing tags/NDJSON lenient,
  katalog, clamp tuning, encode/decode manifest.
- **App (JVM)**: `OllamaLocalTest` — controller + client melawan
  MockWebServer nyata: koneksi, daftar model, pull dengan pause/resume
  (job cancel + re-issue), delete, use-in-chat, import/export.
- **E2E (emulator)**: `LocalAiE2eTest` — UI nyata: buka Agent Settings →
  Local AI → connect ke server Ollama palsu (MockWebServer stateful) →
  model terpasang tampil → instal preset katalog → chip "Installed"
  muncul setelah pull selesai. Label pause/resume sengaja TIDAK
  diassert di e2e (body MockWebServer selesai seketika, fase
  DOWNLOADING terlalu cepat untuk diamati) — semantiknya diuji di
  `OllamaLocalTest`.
- **E2E on-device**: `OnDeviceEngineE2eTest` — model asli
  `smoke-story-260k.gguf` (260K parameter, ~1.13 MB) di-seed ke direktori
  model sebelum layar dibuka → bagian On-device engine muncul → tombol
  **Try** membuktikan stack natif penuh (JNI load + generasi 24 token)
  di emulator x86_64. `LocalLlamaGatewayTest` (JVM) menguji resolusi
  model, pemetaan pesan, dan semua mode kegagalan dengan engine palsu.
