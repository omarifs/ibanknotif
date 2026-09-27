# Log Proyek IBankNotif

Proyek ini adalah aplikasi Android untuk membaca notifikasi bank (BCA, myBCA, Mandiri/Livin', BRI/BRImo) secara otomatis di background dan meneruskan data transaksi ke Telegram Bot via webhook PHP.

> File ini dulunya bernama `CREDENTIALS_AND_LOG.md` dan sempat ter-commit ke
> GitHub lengkap dengan token asli. Sekarang namanya diubah karena isinya
> sudah tidak memuat kredensial sama sekali, dan nilainya dilacak kembali ke
> git karena isinya dokumentasi yang berguna. Kredensial disimpan di luar repo.

---

## 1. Lokasi Proyek & Git Repository

* **Lokasi Lokal:** `/mnt/a/laragon/www/ibanknotif`
* **GitHub Repository:** `https://github.com/omarifs/ibanknotif.git`
* **Branch Utama:** `main`

---

## 2. Kredensial & Autentikasi

Nilai kredensial SENGAJA TIDAK ditulis di file ini. File ini pernah ter-commit
ke GitHub lengkap dengan token asli, jadi nilainya ada di riwayat git dan tidak
dapat dihapus hanya dengan mengubah file.

Simpan kredensial di tempat terpisah yang tidak masuk git, misalnya `.env` lokal
yang di-ignore, atau password manager.

* **GitHub:** pakai SSH, bukan token.
 * Remote: `git@github.com:omarifs/ibanknotif.git`
 * Key: `~/.ssh/ts_ed25519`
 * Token PAT yang pernah tertanam di sini sudah dicabut per 2026-09-27
 (tes `GET /user` -> `Bad credentials`).

* **Telegram Bot & Webhook Endpoint:**
 * Bot Token Telegram: `<RAHASIA - simpan di .env atau password manager>`
 * Chat ID Default: `-1002031892399`
 * PHP Webhook Script (Lokal): `/mnt/a/laragon/www/telegram/telegram.php`
 * Server Webhook Path: `dn-arif:/home/websolusi/telegram/telegram.php`
 * Rules klasifikasi (ditarik app): `https://tools.websolusi.com/telegram/rules.json`
 * Server Endpoint Webhook (Public): `https://tools.websolusi.com/telegram/telegram.php`

---

## 3. Struktur Proyek Android

```text
/mnt/a/laragon/www/ibanknotif/
 .github/workflows/
 build.yml # Workflow GitHub Actions untuk auto-build APK Debug
 app/
 src/main/
 AndroidManifest.xml # Izin Notifikasi, Service & Adaptive Icon
 java/com/websolusi/ibanknotif/
 Config.kt # SharedPreferences, log & bank resolution
 Gemini.kt # Integrasi Gemini API (gemini-2.0-flash) utk Pindai AI
 Learning.kt # Template manager (pola regex + command offline)
 MainActivity.kt # UI Pengaturan, API Key, Tombol Pindai AI & Rebind
 NotifService.kt # Foreground Listener + auto-detect (Learned -> Static)
 Parser.kt # Parser regex nominal, arah (in/out/pindahin), fallback
 Sink.kt # HTTP POST JSON sender
 res/
 drawable/ # Adaptive Icon vector background/foreground
 mipmap-anydpi/ # Launcher icon entries
 layout/activity_main.xml
 CREDENTIALS_AND_LOG.md # Dokumentasi & Log Pembelajaran
```

---

## 4. Workflow Pengembangan & Build

1. `cd /mnt/a/laragon/www/ibanknotif`
2. Modifikasi kode -> `git add .` -> `git commit -m "..."` -> `git push`
3. CI/CD GitHub Actions otomatis mengkompilasi APK -> unduh di tab **Actions Artifacts (`ibanknotif-debug`)**.

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

### I. Stabilisasi Kirim, `/pindahin` Atomic & Catch-up Notif
1. **Fallback `/pindahin` BRI `bri.vidha`:** `Parser.toCommand` default tujuan tanpa rekening terdeteksi kini `bri.vidha` (sebelumnya `tujuan.rek`). `8533` tetap `bri.ibu`.
2. **`Sink.send` antrean FIFO:** 1 worker thread kirim POST berurutan (bukan thread bebas per notif), jeda 500ms antar kirim menghindari input barengan ke webhook. Signatur `send(ctx, cmd, dedupKey)` tidak berubah.
3. **`/pindahin` transaksi DB (`telegram.php`):** `pinout`+`pinin` dibungkus `begin_transaction`/`commit`/`rollback` tidak ada setengah jadi bila salah satu INSERT gagal. Deployed `dn-arif`, test live `pinout` 456 + `pinin` 457 berhasil.
4. **Catch-up (`NotifService.onListenerConnected`):** saat listener reconnect/suspend pulih, otomatis `scanActiveNotifications()` menangkap notif bank yang tertinggal (terutama kasus "hanya notif terakhir yang terbaca"). Guard `isProcessed` + `dedup_key` mencegah dobel.
5. Update ini gabungan dari kendala: (a) input barengan gagal di DB, (b) notif pertama tak terbaca bila service mati, (c) fallback tujuan pindahin keliru.

### H. Dedup Key Setelah Reinstall
1. `Sink` membuat SHA-256 `dedup_key` stabil dari bank, package, waktu posting, dan isi notifikasi.
2. Request aplikasi menyertakan `message.dedup_key`; command Telegram biasa tidak menyertakan field ini dan tetap diproses normal.
3. Webhook PHP memakai tabel `tele_notif_dedup` dengan primary key atomik untuk menolak scan ulang setelah aplikasi di-uninstall/install.
4. Kode `telegram.php` sudah di-commit & di-push ke repository GitHub `omarifs/telegram` (commit `c8fa4bdf`).
5. Kode server produksi `dn-arif:/home/websolusi/telegram/telegram.php` sudah dideploy dan terverifikasi live dengan tes dedup idempotent.

### G. Custom Command, Flow Pindai AI Terpusat & Fix `/pindahin`
1. **Kolom Custom Command (UI):** baris format `bank::command` atau `bank::pattern::command`, placeholder `{amount}` diganti nominal. Contoh: `bri::/pindahin bri.ibu bri.vidha {amount}`. Kolom kosong = flow lama normal (default AI + statis).
2. **Precedence Pindai AI:** custom command cocok langsung dipakai (offline, tanpa AI); sisa Gemini (custom diteruskan ke prompt sebagai preferensi); sisa lagi fallback `Parser` statis (parity dgn Pindai Manual).
3. **Pindai AI terpusat (satu langkah):** klik Pindai otomatis simpan hasil ke template belajar (`Learning.addTemplates`) + kirim ke Telegram (`Sink.send`) + guard duplikat `isProcessed`. Dialog "Simpan Belajar" dihapus.
4. **Auto-detect (`NotifService`):** `Learning.match` cek custom command dulu, baru template belajar; command custom dgn `{amount}` otomatis diisi nominal.
5. **Fix `/pindahin`:** `Parser` tangkap rekening tujuan (`nomor rekening tujuan XXXX3508`) `Transaction.destination`; `toCommand` kini `/pindahin <id_asal> <id_tujuan> <nominal>` (mapping 8533`bri.ibu`, lain`bri.vidha`, fallback `tujuan.rek`), bukan lagi hardcode `tujuan.rek`.
6. **Prompt Gemini toleran:** few-shot contoh BRI/BCA/Mandiri, aturan pindahin asal+tujuan, null hanya utk promo/iklan/tanpa nominal, instruksi regex sederhana IGNORE_CASE.
7. **Kolom Custom Prompt Gemini (UI):** field bebas `bank::command` tetap, tambah field bebas "Custom Prompt" disisipkan ke prompt Gemini sebagai `INSTRUKSI TAMBAHAN DARI PENGGUNA` (prioritas tertinggi, menimpa aturan kamus bila bertentangan). Dipakai saat mapping offline / algoritma lokal gagal. Contoh kasus nyata: transfer BCABRI via Flip (TPT Lentera Global) salah diklasifikasi `tabin`/`tabout`, harusnya `/pindahin bca bri.ibu`. Instruksi ini dipasok lewat kolom custom prompt; hasil scan Gemini tetap otomatis jadi template belajar offline (`Learning.addTemplates`), sehingga NotifService berikutnya deteksi benar tanpa AI & tanpa error ulang.

---

### J. Aturan Klasifikasi Jadi Data (2026-09-27)

Commit: `d277193 feat: aturan klasifikasi jadi data, pindahin tanpa tebakan`
(c preceded by `4dbc0dd chore: berhenti melacak file kredensial & screenshot`)

**1. Akar masalah `/pindahin` yang berulang.** `Parser.toCommand` punya
`else -> "bri.vidha"` sebagai fallback tujuan. Saat rekening tujuan tidak
terbaca, app menebak. Kalau rekening asal juga `bri.vidha`, hasilnya
**self-transfer**: `pinout` dan `pinin` masuk ke rekening yang sama, saldo
keluarga tidak berubah tapi history jadi korup. Itu penyebab baris 497-498.

Kelas bug yang sama juga ada di `PINDAH_RE` yang cocok untuk SEMUA
"Transfer dari ... dengan nomor rekening tujuan ...", tanpa cek apakah
nomor asal itu rekening sendiri. `Transfer dari XXXXXXXXXX5530 ... tujuan
XXXXXXXXXX8533` menghasilkan `/pindahin bri.vidha bri.ibu`, padahal itu
transfer masuk dari pihak luar.

**2. Arah transaksi ditentukan rekening, bukan kata.** Kata `"pindah dana"`,
`"pindah saldo"`, `"ke rekening sendiri"` dulu memicu `pindahin` sebelum
dicek rekening apa pun. Setelah diganti, aturannya:

```
src & dst terbaca, keduanya 8533/3508, berbeda -> /pindahin
src == dst -> diam
src milik sendiri, tujuan luar -> /tabout
dst milik sendiri, asal luar -> /tabin
hanya satu sisi milik sendiri + kata "transfer" -> diam
tidak ada rekening terbaca -> andalkan kata
```

Tiga daftar `PINDAH_*` dihapus. Angka 8533 dan 3508 dipetakan dari
rekening yang tampil di notif, bukan dari `last4` pertama yang ditemukan.

**3. Dua bug yang tidak terlihat dari kode.** `"dana"` ada di
`EWALLET_KEYWORDS`, dan kata itu muncul di hampir semua notif BRI
("Dana Rp... masuk"), sehingga ikut memicu deteksi e-wallet dan membalik
transaksi masuk jadi keluar. Lalu `Config.markProcessed` memakai `apply()`
(async); dua panggilan berurutan bisa saling menimpa, karena panggilan kedua
membaca daftar lama. Guard anti-duplikat jadi bocor tanpa error. Diperbaiki
jadi `vararg` + `commit()` sinkron + `synchronized`.

**4. `tabin` memakai rekening asal, padahal yang nambah saldo rekening
tujuan.** `Transfer dari ...5530 ... tujuan ...8533` menghasilkan
`/tabin bri.vidha`. Sekarang `in` memakai `{id}` dari tujuan, `out` dari
asal.

**5. Refactor utama: keputusan pindah dari kode ke data.** Yang tadinya
`detectDirection` + `accountId` + `toCommand` + `OWN_ACCOUNTS` di dalam
kode, sekarang 13 aturan di `app/src/main/assets/rules.json`. `Parser`
tinggal ekstraksi. Konsekuensinya memperbaiki klasifikasi tidak butuh build
lagi: dari dialog **Aturan Klasifikasi** (editor JSON + uji coba ke notif
aktif + kembalikan 3 versi terakhir), atau tombol **Perbaiki Aturan dengan
AI** yang minta satu aturan ke Gemini lalu memvalidasinya sebelum disimpan.

**6. Kosakata `when` tertutup, tanpa eval.** `src_own`, `dst_own`,
`src_ne_dst`, `has:transfer`, `has_any:a|b|c`, `amount_gt:N`, dan
sebagainya. `RuleValidator` menolak kondisi regex, kondisi yang mengikat
nominal contoh (`has:300000`), id akun tak dikenal, priority bentrok, dan
`/pindahin` yang kurang dua slot. Aturan `stop` menahan evaluasi supaya
satu transfer tidak tercatat sebagai `pindahin` sekaligus `tabout`.

**7. Anti-duplikat tingkat transaksi.** Satu transfer BRI datang sebagai dua
notifikasi (`Sobat BRI! Dana ... masuk` dan `Transfer dari ... tujuan ...`).
`transactionSignature` = `bank|amount|waktu event dari dalam teks notif`.
Karena waktu event sama, notifikasi kedua tertahan. Diperiksa SEBELUM jalur
apa pun supaya template hasil belajar tidak bisa mem-bypass-nya.

**8. Gemini menyusulkan aturan, bukan output mentah.** `suggestRule()`
meminta satu aturan lengkap dengan kosakata yang sah, lalu hasilnya
divalidasi `RuleValidator` sebelum boleh disimpan. `extract()` dan
`suggestRule()` sekarang berbagi satu fungsi `call()`. Prompt bawaan
memuat aturan rekening 8533/3508, bukan hanya di kolom custom prompt, supaya
tidak ikut hilang saat app dipasang ulang.

**9. Redaksi API key di log.** Pesan exception dari `HttpURLConnection`
sempat memuat URL lengkap beserta `?key=`, dan itu yang bocor ke log lalu ke
screenshot. Sekarang di-redaksi sebelum ditulis.

**10. Verifikasi.** `./gradlew` tidak bisa jalan di environment tanpa JDK
dan Android SDK, jadi logika `RuleEngine` + `rules.json` dipporting ke Python
lalu dijalankan dengan test yang sama: **17/17 paritas** dengan perilaku
`Parser` sebelumnya, **19/19 validasi aturan**. 37 test JUnit sudah ditulis
(`app/src/test/java/com/websolusi/ibanknotif/ParserTest.kt`) tapi **belum
pernah dieksekusi**. Yang tidak diverifikasi: sintaks Kotlin-nya, dan kasus
`/pindahin 3508 -> 8533` yang belum pernah terjadi di data nyata.

**11. Produksi.** `rules.json` di-deploy ke
`dn-arif:/home/websolusi/telegram/rules.json` (md5
`2cba44b3c4024ea9b486e6e0231a8513`, `websolusi:websolusi 644`, dapat diakses
publik sebagai `application/json`). `telegram.php` tidak disentuh, md5 tetap
`af4df2e5c03f2ead7c3c8ae11e04f35f`. Sinkron dari app hanya menarik, tidak
menulis, dan menolak menimpa hasil edit manual.

**12. Djebakan `.gitignore`.** `CREDENTIALS_AND_LOG.md` dan screenshot sudah
ada di `.gitignore`, tapi karena file sudah ter-track, `.gitignore` tidak
berlaku dan `git add .` tetap mengambilnya. Itulah sebabnya kredensial masuk
kembali ke history pada 2026-09-05, empat hari setelah sempat dihapus pada
2026-09-01. `.gitignore` hanya berlaku untuk file yang BELUM ter-track.
Perbaikannya `git rm --cached`, bukan `git rm`.

## 6. Daftar Perintah Telegram yang Didukung Handler PHP

- `/tabin <id> <nominal> [YYYY-MM-DD]` Transaksi masuk
- `/tabout <id> <nominal> [YYYY-MM-DD]` Transaksi keluar
- `/pindahin <id_asal> <id_tujuan> <nominal> [YYYY-MM-DD]` Transfer antar rekening (otomatis dicatat sebagai `pinout` & `pinin`)
- `/tabtotal [n_bulan]` Rekap mutasi & saldo tabungan
- `/tabdel <id>` Hapus baris transaksi berdasarkan ID

---

## 7. Playbook: Keputusan yang Berulang Salah Jadi Data, Bukan Kode

Bagian ini bukan catatan kejadian, tapi resep yang bisa dipakai lagi di proyek
mana pun. Semuanya berasal dari refactor yang ari di bagian 5J.

### 7.1 Kapan pola ini tepat

Gunakan kalau keputusan di aplikasi terus salah dan tiap koreksi butuh build
ulang. Sebelum ke sini, cek tangga ini dan berhenti di anak tangga pertama
yang masih berlaku.

1. **Keputusannya benar-benar sering salah?** Kalau jarang, tulis ulang
 kodenya saja. Refactor tidak sepadan.
2. **AI tidak bisa mengubah kode yang sudah dikompilasi.** Ini batas keras.
 Kalau diminta "AI yang perbaiki", jangan mengulang "tapi perlu build" 
 jawaban "tapi perlu build" berulang hanya mengulang kendala yang sama.
3. **Yang bisa dihilangkan build-nya: pindahkan keputusan ke data.** Ini
 inti playbook.
4. Kalau tidak bisa jadi data, baru perbaiki kodenya.

Yang **tidak** hilang dari kebutuhan build: bug struktural, notifikasi yang
memang tidak sampai (masalah izin Android), UI baru, dependensi baru, kode
native. Yang hilang hanya kebutuhan **memperbaiki klasifikasi**.

### 7.2 Bagi tiga lapis dengan tegas

Mencampur ketiganya adalah penyebab refactor ini sulit.

| Lapis | Isi | Boleh diubah user? |
|---|---|---|
| **Ekstraksi** | regex primitif: nominal, nomor rekening, waktu event | tidak, tapi jarang salah |
| **Keputusan** | arah, pemetaan akun, bentuk output | **ya, ini inti** |
| **Pengiriman** | antrean, retry, format request | tidak |

Ekstraksi tetap di kode. Yang jadi data adalah lapis keputusan. Pembagian ini
yang membuat 90 baris `if` berubah jadi 13 entri JSON.

### 7.3 Kosakata tertutup, bukan ekspresi bebas

Aturan memakai daftar token tetap dengan operator AND. OR ditulis sebagai
satu token bergaya pipe. **Jangan** pakai evaluator ekspresi, jangan `eval`,
jangan regex di dalam `when`.

```
jangan: when: "src in accounts && text =~ /dari/"
boleh : when: ["src_own", "has:transfer"]
boleh : when: ["has_any:shopeepay|gopay|ovo", "has_any:top up|isi saldo"]
```

Vocabulary yang menutup hampir semua kasus:

- rekening: `src_own`, `dst_own`, `src_external`, `dst_external`,
 `src_ne_dst`, `src_eq_dst`, `src_known`, `dst_known`
- teks: `has:kata`, `not:kata`, `has_any:a|b|c`, `has_none:a|b|c`
- nominal: `amount_gt:N`, `amount_lt:N`

Kalau sebuah kasus butuh kondisi di luar vocabulary, itu tanda
requirement-nya belum pahami, bukan tanda vocabulary-nya kurang.

### 7.4 Validator yang menolak aturan tidak aman

Aturan bisa datang dari AI atau dari mengetik manual. Keduanya wajib
diperiksa sebelum dipakai. Tolak:

1. Token `when` di luar vocabulary
2. **Nilai contoh** - `has:300000`, atau nominal yang diketik langsung di
 `command`. Ini penyebab utama aturan melekat pada satu transaksi lalu
 salah lagi besok. Aturan harus struktural: dari kata `dari`/`tujuan` atau
 dari rekening, bukan dari nominal contoh.
3. Placeholder yang tidak dikenal
4. Id akun atau nilai output di luar allowlist
5. Bentuk output tidak cocok pola (`/pindahin` wajib dua slot)
6. `priority` bentrok dengan aturan lain untuk bank yang sama

Kalau gagal validasi, **jangan diamkan dan jangan fallback**. Tolak dengan
pesan yang menyebut bagian mana yang salah.

### 7.5 Placeholder, bukan string template bebas

```json
{ "when": ["src_own", "dst_own", "src_ne_dst"],
 "command": "/pindahin {src} {dst} {amount}" }
```

`{src}` `{dst}` `{id}` `{amount}` - daftar tetap. Kalau `command` memakai
`{id}` tapi rekening tidak terbaca, pakai `defaultId` yang declared di aturan
itu. Kalau tetap tidak terpenuhi, aturan tidak_match dan evaluasi lanjut ke
aturan berikutnya.

### 7.6 Kalau tidak tahu, lebih baik diam daripada menebak

Ini bagian yang paling sering jadi bug. Fallback seperti
`else -> "akunCadangan"` diam-diam menulis data yang salah, dan kalau inputnya
uang, itu merusak angka yang tidak bisa dikembalikan sendiri.

```
pindahin hanya kalau src dan dst keduanya rekening sendiri dan berbeda
hanya satu sisi terbaca -> jangan jadi tabin/tabout, biarkan pemanggil memutuskan
src == dst -> jangan catat apa pun
```

Aturan dengan `stop: true` menghentikan evaluasi tanpa mengirim. Pakai untuk
kasus yang harus diam supaya notifikasi pasangannya yang mencatat. Kalau
"diam" punya makna bisnis, jadikan itu property aturan, bukan kondisi yang
kebetulan tidak cocok.

### 7.7 Anti-duplikat di dua lapis

Satu peristiwa bisa jadi beberapa notifikasi.

1. **Tanda tangan transaksi** dari data **di dalam** teks, bukan waktu
 server. Untuk notifikasi bank, waktu event ada di badan notif.
 `bank|amount|eventTimestamp`. Dua notif satu peristiwa punya tanda tangan
 sama, jadi yang kedua tertahan.
2. **Deduplikasi per notifikasi** - biasanya sudah ada, tapi hilang saat app
 dipasang ulang karena disimpan lokal.

Tanda tangan harus dihitung **sebelum** jalur mana pun, supaya jalur lain
tidak bisa mem-bypass-nya.

### 7.8 Perhatikan async saat menandai "sudah diproses"

Dua panggilan `apply()` berurutan bisa saling menimpa: panggilan kedua
membaca daftar lama karena yang pertama belum commit, lalu menulis daftar
lama PLUS key-nya - key yang pertama hilang. Guard anti-duplikat jadi bocor
tanpa error apa pun.

```kotlin
// salah: key pertama bisa hilang
markProcessed(a); markProcessed(b)

// benar: satu kali tulis, sinkron
fun markProcessed(ctx: Context, vararg keys: String) = synchronized(p) {
 p.edit().putStringSet(KEY, current + keys).commit()
}
```

`synchronized` juga menutup race antara thread listener dan thread catch-up
yang memproses notifikasi bersamaan.

### 7.9 Fungsi di jalur notifikasi tidak boleh menyentuh jaringan

Kalau `load()` atau `evaluate()` dipanggil dari dalam
`NotificationListenerService`, satu panggilan HTTP di sana akan menahan
pemrosesan notifikasi. Tarikan dari server harus dipisah, dipanggil hanya dari
UI atau startup.

### 7.10 Urutan aman saat memindahkan keputusan ke data

Jangan langsung mengganti. Bertahap, dan simpan test paritas.

1. Tulis engine + data default **di samping** logika lama
2. Jalankan keduanya pada input yang sama, bandingkan hasilnya
3. Kalau 100% sama pada seluruh sampel, baru hapus yang lama
4. Uji validasi dengan aturan bermasalah, pastikan ditolak

Langkah 2 yang membuat refactor ini aman. Tanpanya, refactor berjalan dengan
asumsi, dan asumsi itu yang paling mahal saat datanya uang.

### 7.11 AI menulis aturan, bukan output mentah

Kalau AI boleh memperbaiki sendiri, jangan minta command akhir. Minta **satu
aturan**, lalu validasi di sisi app.

- Prompt menyebut vocabulary secara literal
- Aturan hasil AI wajib lolos validator yang sama
- Tampilkan before/after, minta persetujuan sebelum menyimpan
- Hasil yang valid ikut tersimpan, jadi besok tidak perlu AI lagi untuk
 kasus yang sama

Kalau output mentah AI bisa langsung dikirim ke sistem produksi, ada trust
boundary yang bocor: teks notifikasi yang tidak dikontrol + instruksi bebas
= command arbitrer.

### 7.12 Baca sampel input nyata sebelum menulis aturan

Jangan menulis regex dari deskripsi. Baca teks notifikasi sungguhan, lalu
normalisasi angkanya untuk melihat berapa banyak *skeleton* berbeda. Kalau 9
notif cuma jadi 3-5 skeleton,itelistnya memang tertutup. Kalau jadi 9, ada
variasi yang belum dipahami.

Skeleton itu dipakai sebagai fixture test. Bukan contoh karangan - contoh
karangan tidak pernah menangkap varyasi yang sebenarnya terjadi.

### 7.13 Secret hygiene

- **`.gitignore` hanya berlaku untuk file yang belum ter-track.** Kalau file
 pernah ter-commit, `git add .` tetap mengambilnya. Perbaikannya
 `git rm --cached`, bukan `git rm`.
- **Rotasi yang benar-benar dicabut**: jangan percaya, uji terhadap layanannya.
 Telegram `getMe` -> `ok: true` masih hidup. GitHub `GET /user` ->
 `Bad credentials` sudah mati.
- **Rotasi dulu, bersihkan kemudian.** Mengubah file tidak menegakkan nilai
 lama.
- **Jangan taruh secret di URL remote.** Pakai SSH, atau credential helper.
 URL remote ikut terbaca di setiap pesan error, output `git remote -v`, dan
 log CI.
- **Jangan log string rahasia**, termasuk pesan exception. `HttpURLConnection`
 bisa memuat URL lengkap beserta query string.
