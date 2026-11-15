# AutoClip AI v0.5 — Android + FastAPI source

## Fitur yang tersedia di source ini
- Memilih video dari penyimpanan Android.
- Mengirim video ke backend untuk transkripsi dan pemilihan momen berbasis instruksi AI.
- Membuat video vertikal 9:16 dengan subtitle hasil transkripsi.
- Preview video sumber dan hasil clip.
- Editor rentang waktu berbasis slider dan penggantian subtitle untuk render ulang clip.
- Pemeriksaan status server dan kesiapan API AI.
- Backend menolak proses AI jika `OPENAI_API_KEY` belum disetel; tidak menyamarkan hasil demo sebagai hasil AI.
- Validasi ekstensi dan stream video memakai `ffprobe` sebelum pemrosesan.
- Upload Android dikirim secara streaming agar video besar tidak perlu dimuat seluruhnya ke RAM.
- URL backend dapat diatur saat build tanpa mengedit source Kotlin.

## Kebutuhan backend
- Python 3.10+ (disarankan 3.11/3.12), FFmpeg + ffprobe di PATH.
- API key OpenAI disetel hanya pada server.

```bash
cd backend
python -m venv .venv
# aktifkan virtual environment sesuai OS
pip install -r requirements.txt
# Linux/macOS
export OPENAI_API_KEY='isi-api-key-di-server'
uvicorn main:app --host 0.0.0.0 --port 8000
```

`MAX_UPLOAD_MB` opsional (default 300). Jalankan backend di mesin/server yang dapat dijangkau perangkat Android.

## Konfigurasi Android
Buka `android/app/build.gradle.kts`, ubah `API_BASE_URL` sesuai alamat backend dan akhiri dengan `/`.
- Android Emulator: `http://10.0.2.2:8000/` (pengembangan lokal).
- HP fisik: gunakan alamat LAN komputer/server untuk pengujian lokal, atau HTTPS domain server untuk penggunaan nyata.

Jangan gunakan HTTP terbuka di produksi. Untuk rilis, aktifkan HTTPS, autentikasi, pembatasan kuota, penyimpanan privat, penghapusan otomatis file sumber/hasil, rate limiting, dan monitoring. Jangan pernah memasukkan API key ke APK.

## Build APK
Buka folder `android` menggunakan Android Studio versi terbaru, pastikan Android SDK platform 35 terpasang, lalu pilih **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

URL backend dapat diatur saat build:

```bash
./gradlew assembleDebug -PAUTOCLIP_API_BASE_URL=https://domain-server-anda/
```

Untuk emulator lokal, default tetap `http://10.0.2.2:8000/`. HTTP cleartext hanya diizinkan untuk konfigurasi non-HTTPS; gunakan HTTPS untuk server publik.

## Batasan yang belum diklaim selesai
- Editor timeline saat ini mengatur rentang awal/akhir melalui slider, belum waveform/frame-by-frame.
- Pemilihan momen AI menggunakan transkrip audio; belum memahami keseluruhan adegan visual.
- Deteksi wajah dan pelacakan pembicara belum diimplementasikan. Deteksi wajah saja juga tidak cukup untuk memastikan siapa yang sedang berbicara.
- Belum ada pengujian di perangkat Android fisik, deployment backend publik, autentikasi pengguna, atau APK release yang ditandatangani.

## Build APK tanpa laptop (GitHub Actions)

1. Buat repository GitHub baru melalui browser HP.
2. Upload seluruh isi folder `AutoClipAI` ke repository (termasuk folder `.github`).
3. Buka tab **Actions**, pilih **Build AutoClip AI APK**, lalu tekan **Run workflow**.
4. Tunggu workflow selesai. Buka hasil run, bagian **Artifacts**, lalu unduh `AutoClipAI-debug-apk`.
5. Ekstrak ZIP artifact di HP; APK debug ada di dalamnya.

Workflow ini menyiapkan APK debug untuk pengujian, bukan rilis produksi bertanda tangan pribadi. Untuk menjalankan AI, backend harus di-deploy ke server HTTPS dan `OPENAI_API_KEY` disetel sebagai secret di server. Jangan masukkan API key ke repository atau APK.
