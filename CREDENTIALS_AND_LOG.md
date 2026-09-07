# Dokumentasi & Kredensial Proyek IBankNotif

Proyek ini adalah aplikasi Android untuk membaca notifikasi bank (BCA, myBCA, Mandiri/Livin', BRI/BRImo) secara otomatis di background dan meneruskan data transaksi ke Telegram Bot via webhook PHP.

---

## 1. Lokasi Proyek & Git Repository

* **Lokasi Lokal:** `/mnt/a/laragon/www/ibanknotif`
* **GitHub Repository:** `https://github.com/omarifs/ibanknotif.git`
* **Branch Utama:** `main`

---

## 2. Kredensial & Autentikasi

* **GitHub Personal Access Token (Classic):**
  * Token: `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`
  * Git Remote URL (dengan Token): `https://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@github.com/omarifs/ibanknotif.git`

* **Telegram Bot & Webhook Endpoint:**
  * Bot Token Telegram: `8533838625:AAEeeIpUS8ubYwaVBS0U_Ob0RiHvcptDu80`
  * Chat ID Default: `-1002031892399`
  * PHP Webhook Script (Lokal): `/mnt/a/laragon/www/telegram/telegram.php`
  * Server Webhook Path: `dn-arif:/home/websolusi/telegram/telegram.php`
  * Server Endpoint Webhook (Public): `https://tools.websolusi.com/telegram/telegram.php`

---

## 3. Struktur Proyek Android

```text
/mnt/a/laragon/www/ibanknotif/
├── .github/workflows/
│   └── build.yml               # Workflow GitHub Actions untuk auto-build APK Debug
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml # Izin Notifikasi, Service & Adaptive Icon
│   │   ├── java/com/websolusi/ibanknotif/
│   │   │   ├── Config.kt       # SharedPreferences, log & bank resolution
│   │   │   ├── Gemini.kt       # Integrasi Gemini API (gemini-2.0-flash) utk Pindai AI
│   │   │   ├── Learning.kt     # Template manager (pola regex + command offline)
│   │   │   ├── MainActivity.kt # UI Pengaturan, API Key, Tombol Pindai AI & Rebind
│   │   │   ├── NotifService.kt # Foreground Listener + auto-detect (Learned -> Static)
│   │   │   ├── Parser.kt       # Parser regex nominal, arah (in/out/pindahin), fallback
│   │   │   └── Sink.kt         # HTTP POST JSON sender
│   │   └── res/
│   │       ├── drawable/       # Adaptive Icon vector background/foreground
│   │       ├── mipmap-anydpi/  # Launcher icon entries
│   │       └── layout/activity_main.xml
└── CREDENTIALS_AND_LOG.md      # Dokumentasi & Log Pembelajaran
```

---

## 4. Workflow Pengembangan & Build

1. `cd /mnt/a/laragon/www/ibanknotif`
2. Modifikasi kode -> `git add .` -> `git commit -m "..."` -> `git push`
3. CI/CD GitHub Actions otomatis mengkompilasi APK -> unduh di tab **Actions → Artifacts (`ibanknotif-debug`)**.

---

## 5. Log Pembelajaran & Perbaikan Sistem (Update Terakhir)

### A. Perbaikan Parser & Logika Transaksi (`Parser.kt` & `NotifService.kt`)
1. **Top-Up E-Wallet BRI (`/tabout bri.ibu <nominal>`):**
   - Kasus: BRImo notifikasi `Top Up ShopeePay ... Rp200.000` sebelumnya salah diklasifikasi sebagai `in` karena kata `"top up"`.
   - Solusi: Kata kunci top-up dipindahkan ke `OUT_KEYWORDS`. Khusus notifikasi BRI ber-e-wallet -> otomatis dijadikan `out` dan jika `last4 == null` (tidak ada nomor rekening awal di notif), default ke `bri.ibu`.
2. **Transfer Antar Rekening Sendiri (`/pindahin`):**
   - Pola notifikasi BRI `"Transfer dari XXXXX8533 dengan nomor rekening tujuan XXXXX3508"` kini ditangkap oleh `PINDAH_RE` -> menghasilkan `/pindahin bri.ibu tujuan.rek <nominal>`.
   - `PINDAH_KEYWORDS` dan `PINDAH_OWN_RE` dievaluasi di urutan **prioritas tertinggi** sebelum check `in`/`out`.

### B. Integrasi Gemini AI & Learning Offline (`Gemini.kt` & `Learning.kt`)
1. **Pindai AI (Scan Manual):**
   - Menambahkan input manual **Gemini API Key** di Pengaturan.
   - Pindai AI mengambil seluruh notifikasi bank aktif di HP -> dikirim ke `gemini-2.0-flash` -> Gemini mengekstrak data + memformulasikan `command` Telegram final (`/tabin`, `/tabout`, `/pindahin`) + menghasilkan `pattern` (regex Kotlin).
2. **Hasil Belajar Offline (Template Manager):**
   - Hasil scan AI yang disetujui user disimpan di `SharedPreferences` sebagai **template hasil belajar** (offline, tanpa panggil AI ulang).
   - Pada runtime `NotifService.onNotificationPosted`, sistem **mencoba mencocokkan template hasil belajar dulu** (`Learning.match`). Jika cocok -> langsung kirim command hasil belajar. Jika tidak cocok -> fallback ke `Parser` statis.

### C. Solusi Race Condition Server PHP & DB (`telegram.php` & MySQL)
1. **Penyebab Data Hilang Saat Scan Manual:**
   - Gejala: Log HP mencatat 3 HTTP POST `200`, tetapi di Telegram hanya 1 transaksi terakhir yang muncul.
   - Penyebab: `telegram.php` menghitung `id` via `SELECT MAX(id)+1 FROM tele_tabungan`. Saat 3 POST dikirim simultan dari HP, 3 request membaca `MAX(id)` yang sama secara paralel -> `PRIMARY KEY` (id) bentrok -> 2 INSERT ditolak DB.
2. **Solusi Permanen (Server `dn-arif`):**
   - Mengubah kolom `id` pada tabel `tele_tabungan` dan `tele_tabungan_emas` di MySQL menjadi **`AUTO_INCREMENT`**.
   - Menghapus panggilan `maxID()` manual pada `INSERT` (`/tabin`, `/tabout`, `/pindahin`, `/emasin`) di `/home/websolusi/telegram/telegram.php`. DB sekarang menggenerate ID atomic yang aman dari race condition. Uji race 5 request simultan -> 100% sukses masuk DB.

### D. Perbaikan UX App & Launcher Icon
1. **Diagnosis Service Status (`MainActivity.kt`):**
   - Membedakan kondisi **"Izin Notifikasi Mati"** (buka dialog pengaturan) vs **"Service Di-suspend Sistem"** (`instance == null` meskipun izin aktif).
   - Menambahkan `NotificationListenerService.requestRebind()` otomatis saat service suspend agar listener aktif kembali tanpa membingungkan user.
2. **Adaptive Launcher Icon:**
    - Menambahkan vector icon resmi `@mipmap/ic_launcher` (koin rupiah emas + grafik pertumbuhan naik di atas background gradient navy-indigo-teal).

### E. Recovery Service Setelah Reboot
1. `MainActivity` mencoba mendapatkan `NotifService.instance` otomatis setelah `requestRebind()` dengan jeda 1, 2, dan 4 detik.
2. Retry berhenti saat service aktif atau setelah tiga percobaan; tidak ada polling permanen atau `BOOT_COMPLETED` receiver.
3. Redmi Note 8/MIUI perlu IBankNotif disetel ke **No restrictions**, **Autostart aktif**, dan dikunci pada Recent apps agar listener tidak disuspend sistem.
4. Build GitHub Actions menggunakan JDK 17 dan menghasilkan artifact `ibanknotif-debug`.
5. `BootReceiver` ditambahkan untuk memanggil `NotificationListenerService.requestRebind()` setelah `BOOT_COMPLETED` jika akses notifikasi masih aktif.
6. Manifest menambahkan permission `RECEIVE_BOOT_COMPLETED` dan receiver `.BootReceiver`.
7. Receiver tidak menjalankan proses berat, tidak membaca notifikasi, dan tidak memaksa izin. MIUI tetap perlu **Autostart aktif**, baterai **No restrictions**, dan aplikasi dikunci pada Recent apps.
8. Validasi lokal belum berjalan karena environment tidak memiliki Java/JAVA_HOME.

### F. Crash `startForeground` di NotificationListenerService (Redmi/MIUI)
1. Gejala: izin notifikasi OK, log `SERVICE REBIND diminta (izin OK, instance null)`, instance tetap null setelah retry.
2. Penyebab: `NotifService.onCreate()` memanggil `startForeground()`. Android 14/MIUI menolak FGS dari listener ter-bind -> service crash sebelum `onListenerConnected`.
3. Solusi: hapus FGS; set `instance` hanya di `onListenerConnected`; `requestRebind()` tanpa toggle komponen (toggle MIUI bisa cabut izin listener).
4. Scan menunggu `connected=true`. Jika gagal: dialog buka akses notifikasi/baterai. Versi app `1.0.1` (versionCode 2).

### G. Custom Command, Flow Pindai AI Terpusat & Fix `/pindahin`
1. **Kolom Custom Command (UI):** baris format `bank::command` atau `bank::pattern::command`, placeholder `{amount}` diganti nominal. Contoh: `bri::/pindahin bri.ibu bri.vidha {amount}`. Kolom kosong = flow lama normal (default AI + statis).
2. **Precedence Pindai AI:** custom command cocok → langsung dipakai (offline, tanpa AI); sisa → Gemini (custom diteruskan ke prompt sebagai preferensi); sisa lagi → fallback `Parser` statis (parity dgn Pindai Manual).
3. **Pindai AI terpusat (satu langkah):** klik Pindai → otomatis simpan hasil ke template belajar (`Learning.addTemplates`) + kirim ke Telegram (`Sink.send`) + guard duplikat `isProcessed`. Dialog "Simpan Belajar" dihapus.
4. **Auto-detect (`NotifService`):** `Learning.match` cek custom command dulu, baru template belajar; command custom dgn `{amount}` otomatis diisi nominal.
5. **Fix `/pindahin`:** `Parser` tangkap rekening tujuan (`nomor rekening tujuan XXXX3508`) → `Transaction.destination`; `toCommand` kini `/pindahin <id_asal> <id_tujuan> <nominal>` (mapping 8533→`bri.ibu`, lain→`bri.vidha`, fallback `tujuan.rek`), bukan lagi hardcode `tujuan.rek`.
6. **Prompt Gemini toleran:** few-shot contoh BRI/BCA/Mandiri, aturan pindahin asal+tujuan, null hanya utk promo/iklan/tanpa nominal, instruksi regex sederhana IGNORE_CASE.

---

## 6. Daftar Perintah Telegram yang Didukung Handler PHP

- `/tabin <id> <nominal> [YYYY-MM-DD]` — Transaksi masuk
- `/tabout <id> <nominal> [YYYY-MM-DD]` — Transaksi keluar
- `/pindahin <id_asal> <id_tujuan> <nominal> [YYYY-MM-DD]` — Transfer antar rekening (otomatis dicatat sebagai `pinout` & `pinin`)
- `/tabtotal [n_bulan]` — Rekap mutasi & saldo tabungan
- `/tabdel <id>` — Hapus baris transaksi berdasarkan ID
