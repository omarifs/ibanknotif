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

### K. Empat Rounds Build Gagal, Akar Penyebabnya (2026-09-27)

Commit: `a262539` (XML), `119d617` (tiga sintaks), `ccd6a2e` (`.entries`).
Semua tertangkap CI, bukan build lokal.

**1. `:app:processDebugResources` - dua attribute XML salah** di
`dialog_rules.xml`, keduanya salah ketik yang saya tulis sendiri.
`android:spellCheckLanguage` tidak dikenal dan dihapus;
`android:inputType="textMultiLine|noSuggestions"` tidak valid karena tokennya
`textNoSuggestions`, bukan `noSuggestions`.

**2. `:app:compileDebugKotlin` - satu keyword jadi sumber semua error.**
`Rule.kt` menulis `val when: List<String>`. `when` adalah keyword Kotlin,
jadi parse gagal di situ. Semua error berikutnya - `Expecting '{'`,
`receiver type mismatch`, `Unresolved reference: entries` - adalah efek
beruntun, termasuk di `Gemini.kt`, `RuleStore.kt`, dan `MainActivity.kt`
yang hanya mereferensikannya.

Pelajaran: kalau tiba-tiba banyak "Expecting '{'" sekaligus di beberapa file,
periksa dulu file yang **paling atas** disebut di daftar error. Di situ
biasanya ada satu penyebab tunggal.

Kunci JSON tetap `"when"` karena itu kontrak dengan prompt Gemini, jadi 13
aturan di `assets/rules.json` tidak perlu disentuh. Yang diganti hanya nama
property Kotlin-nya, menjadi `conditions`.

**3. String literal tidak tertutup** di `Gemini.kt:230`. Baris itu berakhir
dengan `\"`, jadi kutipannya ter-escape dan string tidak pernah tutup; baris
berikutnya jadi kelanjutan. Gejalanya `Unexpected token in a string template`.
Berkas yang ditulis lewat heredoc paling rawan pola ini.

**4. `set.entries` dan `current.entries` - bug yang sama, dua kali.**
`RuleSet` punya `accounts`, bukan `entries`. Yang pertama di
`RuleStore.kt:158`, yang kedua di `Gemini.kt:246` pada sesi yang sama,
setelah yang pertama sudah diperbaiki. Yang kedua seharusnya tertangkap pola
yang sama kalau setelah fix selalu `grep` seluruh project, bukan hanya
lokasi yang dilaporkan.

**Yang tidak tervalidasi secara lokal.** Environment ini tidak punya JDK
maupun Android SDK. Pemeriksaan yang saya lakukan - balance kurung kurawal,
port logika ke Python, scan keyword, scan kutip ganjil - semuanya proves
bentuk, bukan kebenaran kompilasi. Balance kurung kurawal bahkan buta
terhadap dua kelas kesalahan di atas, karena kurungnya sendiri tetap
seimbang. Setelah build hijau, 37 test JUnit di `app/src/test/` baru
pertama kali benar-benar dieksekusi.

### L. `defaultId` Menebak Akun, Rekening `3508` Salah (2026-09-28)

**Gejala.** Empat baris `bri ibu` −200.000 pada 2026-09-28 (id 502-505),
padahal uangnya keluar dari rekening HP vidha. Tidak ada satu pun baris untuk
vidha hari itu.

**Penyebab, dua lapis.**

1. Notifikasi BRImo bentuk kaya tidak pernah menyebut nomor rekening dengan
   4 digit terakhir. Yang muncul `0102 **** **** 530` - hanya **tiga** digit.
   `Parser.extractLast4` butuh empat (`[Xx*].{0,3}(\d{4})\b`), jadi
   `source` dan `destination` kosong. Waktu itu `render()` jatuh ke
   `defaultId`, dan ketiga aturan kata kunci memakai `defaultId: "bri.ibu"`.
   Jadi tebakan itu benar di HP ibu dan pasti salah di HP vidha. Perbaikiannya
   bukan mengganti tebakannya, tapi **menghapus** `defaultId` dan menambah
   aturan `diam` priority 60 (`neither_account_known`) supaya "tidak tahu"
   menjadi keputusan, bukan kebetulan tidak cocok.
2. `accounts` menulis `3508 = bri.vidha`, padahal rekening vidha berakhiran
   `5530` dan `3508` adalah rekening Flip. Akibatnya transfer dari
   `5530 -> 8533` terbaca "transfer masuk dari pihak luar" dan dicatat
   `/tabin bri.ibu` - uang vidha dihitung sebagai pemasukan ibu tanpa
   mengurangi saldo vidha sama sekali.

**Yang dipoles.** `defaultId` dihapus dari `bri-topup-ewallet`,
`bri-dana-masuk`, `bri-dana-keluar`. `accounts` jadi `{8533, 5530}`. Aturan
`diam` baru priority 60 - di atas aturan kata kunci, di bawah semua aturan
struktural; syaratnya saling lepas sehingga tidak pernah membayangi
`pindahin`. `version` 1 -> 2 (tanpa ini `fetchFromServer` berhenti di
`RuleStore.kt:132` dan tidak melakukan apa pun). `Rule.defaultAccounts()`
disamakan supaya `RuleValidatorTest` tidak gagal.

**Tiga test sudah merah sebelum perubahan ini.** Bukan efek refactor, dan
baru ketahuan sekarang kalau test suite-nya belum pernah hijau:

- `dua notif satu transfer menghasilkan tanda tangan sama` memakai dua fixture
  dengan waktu event **berbeda** (`03/09/2026 07:12:49` vs
  `31/08/2026 17:11:17`), jadi `assertEquals` tidak mungkin lulus. Diganti dua
  test: pasangan dengan waktu event sama (dibentuk, bukan transkrip), dan satu
  test yang mengunci keterbatasan sebenarnya - tanda tangan hanya menahan
  kalau **kedua** notif satu peristiwa membawa waktu event yang sama.
- `kondisi yang terikat nominal contoh ditolak` dan `id akun yang tidak
  dikenal ditolak` menguji **teks pesan**, bukan penolakan. Keduanya tetap
  ditolak dengan benar, tapi pesannya berbeda: `has:300000` lebih dulu gagal
  cocok `HAS` (`^(has|not):[a-z]…` tidak bisa diawali angka) sehingga cabang
  "memuat angka" di `checkToken` adalah **kode mati**; dan `/tabout bni.baru
  {amount}` lebih dulu gagal cocok `COMMAND_RE` karena cek slot jalan
  sesudahnya, sehingga pesan "tidak dikenal" juga tidak pernah tercapai.
  Dua test ini dibiarkan merah - perbaikannya mengubah urutan cek di
  `RuleValidator`, di luar lingkup perbaikan ini.

**Verifikasi.** Tidak ada JDK maupun Android SDK di environment ini, jadi
`./gradlew` tidak bisa dijalankan. Yang dilakukan: `Parser.kt`, `Rule.kt`, dan
`RuleEngine.kt` diport ke Python lalu seluruh fixture `ParserTest.kt` dan
`RuleValidatorTest` dijalankan ulang (56 pemeriksaan, semua lulus), plus
`validateAll` atas `assets/rules.json`. Bentuk, bukan kebenaran kompilasi -
`./gradlew testDebugUnitTest` tetap harus jalan di mesin yang punya SDK, dan
 dua test merah di atas akan muncul.

**Belum tertutup, perlu build.** Prompt AI masih menyebut `3508 = bri.vidha`
dan masih menyuruh `pakai "bri.ibu"` untuk notif tanpa rekening
(`Gemini.kt:102,123`), dan contoh `Gemini.kt:131-134` masih menyatakan
`5530` bukan rekening sendiri - sekarang terbukti salah. Sampai ada build,
tombol **Pindai AI** tidak boleh dipakai. Tombol **Bawaan** juga mengembalikan
`rules.json` versi lama yang tertanam di APK, jadi tidak boleh dipakai
setelah sinkron.

**Belum tertutup dan tidak bisa dari data.** Satu peristiwa top up menjadi
beberapa baris. `EVENT_TS_RE` (`Parser.kt:60`) hanya pola
`dd/MM/yyyy HH:mm:ss`, sedangkan notif BRImo menulis
`28 September 2026, 07:36:51 WIB`, jadi `transactionSignature` null dan
guard anti dobel dilewati. `notifKey` memuat `postTime` sehingga tiap notif
tetap punya kunci sendiri. Perbaikannya satu baris regex di `Parser.kt`.

**Temuan lain dari audit read-only `tele_tabungan`.** Kolom `nama` pecah jadi
empat nilai untuk dua rekening: `bri vidha`(69) + `brividha`(42),
`bri ibu`(110) + `briibu`(27). Jadi saldo per rekening dihitung terpisah dan
salah. Ada juga akun sampah `tujuan rek`(1, sisa fallback lama) dan
`normalisasi`(4). `bcaarif`(3) rekening nyata tapi tidak ada di `KNOWN_IDS`.
Tabel tidak punya kolom sumber, jadi baris `tabin` yang sebenarnya transfer
dari vidha tidak bisa dibedakan dari pemasukan asli - hanya bisa dicocokkan
manual ke mutasi bank.

### M. Waktu Event Dinormalkan, Prompt AI Jadi Data (2026-09-28)

Lanjutan 5L. Bagian 5L menutup salah akun lewat data. Yang tersisa adalah
dua hal yang tidak bisa ditutup dengan data, dan satu yang bisa.

**1. Satu peristiwa jadi beberapa baris -> waktu event dinormalkan.**

`EVENT_TS_RE` hanya mengenali `dd/MM/yyyy HH:mm:ss`, sedangkan notif BRImo
bentuk kaya menulis `28 September 2026, 07:36:51 WIB`. `transactionSignature`
jadi null dan guard anti dobel dilewati. Akar masalahnya bukan cuma pola yang
kurang: `transactionSignature` menempelkan teks yang ia temukan apa adanya,
jadi dua notif satu peristiwa yang beda format tulisannya tetap menghasilkan
tanda tangan berbeda. Perbaikannya **normalisasi**, bukan menambah pola:
`Parser.eventTs` menerima dua bentuk dan membentuk satu string kanonik
`yyyy-MM-dd HH:mm:ss`. Nama bulan dipetakan lewat tabel, tanpa `java.time`
dan tanpa dependensi baru.

Test `tanggal panjang BRImo tidak menghasilkan tanda tangan transaksi` yang
ditambahkan di 5L harus dibalik, karena dia sedang mengunci bug ini. Sekarang
ia mengunci bentuk kanoniknya.

Batasnya, dan ini jujur: ini **mengurangi** duplikasi, tidak menghilkannya.
Notif myBCA tidak punya waktu event sama sekali, dan dua notif satu peristiwa
yang waktu eventnya berbeda tetap lolos. Untuk menutup sepenuhnya, `dedup_key`
server-side (`Sink.dedupKey`) masih memuat `postTime`, jadi tiap notifikasi
punya kunci sendiri. Itu perubahan yang lebih besar dan menyentuh
`telegram.php`.

**2. Prompt AI jadi data, dikirim dari server.**

`buildPrompt` (tombol Pindai AI) punya kamus ber-hardcode yang menyebut
`3508 = bri.vidha` dan menyuruh "Untuk BRI tanpa nomor rekening di notif, pakai
`bri.ibu`". Persis tebakan yang dihapus di 5L. Damparannya lebih besar dari
tebakan di rules: Pindai AI **menyimpan hasilnya jadi template belajar**
(`Learning.addTemplates`) yang urutannya **di depan rules** (`NotifService`),
jadi satu kali penekanan tombol bisa membuat aturan yang baru benar tidak
pernah dibaca lagi.

Hanya menyunting string di `Gemini.kt` tidak menyelesaikan itu, karena setiap
perubahan prompt butuh build ulang. Sekarang prompt diambil dari
`dn-arif:/home/websolusi/telegram/prompt.txt` setiap kali Pindai AI ditekan,
dengan urutan: server, hasil tarikan terakhir (cache `ai_prompt_cache`), lalu
`Gemini.PROMPT_BAKAN` di APK. Sumber yang dipakai masuk ke log
(`PROMPT: dari server (# v2 - 2026-09-28)`) supaya versi mana yang aktif
kelihatan. Tarikan tidak pernah dipanggil dari `NotifService`; hanya dari
tombol, yang sudah jalan di thread terpisah.

Pembagian yang membuat kelas bug ini mustahil terulang: **angka rekening
disuntik dari `rules.json`**, kebijakan disimpan di `prompt.txt`. Jadi tidak
ada lagi tempat yang bisa menulis `3508` sendiri. Peta rekening hanya perlu
diubah di satu file, dan itu sudah ada tombolnya.

`buildRulePrompt` (tombol Minta Usulan Aturan) ternyata **sudah** dinamis
sejak 5J: kosakata, `KNOWN_IDS`, peta rekening, dan seluruh daftar aturan
disuntik dari `RuleSet`. Yang perlu dibetulkan di sana cuma skema keluarannya
yang masih menawarkan `"defaultId":"bri.ibu"` - field itu sudah tidak ada di
`rules.json`, dan sama sekali tidak boleh dipakai: kasus yang tidak bisa
dipastikan adalah `"stop": true`, bukan menebak akun.

**3. Dua test merah yang sudah merah sebelumnya, sekarang hijau.**

- `RuleSet.checkToken` mengecek `EXAMPLE_NUMBER` **sesudah** cek format, padahal
  `HAS` menolak token berawalan angka lebih dulu. Cabang "memuat angka" adalah
  kode mati. Urutan dibalik.
- `RuleSet.SLOT` hanya menerima empat id yang dikenal, sehingga `/tabout
  bni.baru {amount}` ditolak sebagai "command tidak cocok pola" sebelum cek slot
  jalan, dan pesan "id akun tidak dikenal" tidak pernah tercapai. `SLOT`
  dilebarkan ke `[\w.]{1,40}`; penolakan tetap terjadi di cek slot, hanya
  pesannya yang benar.

**Catatan migrasi tanda tangan.** Bentuk tanda tangan berubah dari
`dd/MM/yyyy HH:mm:ss` menjadi `yyyy-MM-dd HH:mm:ss`. Key yang tersimpan dalam
`processed_keys` jadi tidak berlaku untuk notifikasi lama. Dampaknya kecil:
satu notifikasi lama masih aktif bisa diproses ulang sekali, dan `notifKey`
yang memuat `postTime` tetap menahannya.

**Verifikasi.** Tetap tanpa JDK di environment ini. `Parser`, `Rule`, dan
`RuleEngine` diport ke Python lagi, sekarang dengan logika `eventTs`, dan
seluruh fixture dijalankan ulang: 55 pemeriksaan, semua lulus, termasuk dua
test `RuleValidatorTest` yang sebelumnya merah. Bentuk, bukan kebenaran
kompilasi.

**Yang masih tidak tertutup.** `dedup_key` server-side masih memuat
`postTime`, jadi satu peristiwa masih bisa masuk lebih dari satu kali kalau
notifikasi pasangannya membawa waktu event berbeda. Perbaikannya di sisi PHP,
di luar lingkup perubahan ini.

### N. Pola Notifikasi dan Allowlist Id Jadi Data (2026-09-28)

Lanjutan 5L dan 5M. Batasnya: HP vidha tidak bisa di-install ulang, jadi APK
di sana **membeku**. Yang bisa menjangkau vidha hanya file yang dibaca rules
engine. Sisanya baru aktif setelah APK baru terpasang.

**Aturan kompatibilitas yang jadi syarat.** Semua file di server harus tetap
valid bagi APK yang sekarang terpasang di vidha. Itu yang membatasi
`knownIds`: daftar itu boleh memuat id baru, tapi **aturan mana pun tidak boleh
memakai id itu** - kalau dipakai, validator APK lama menolak seluruh file dan
vidha membeku di versi lama. Diverifikasi dengan menyimulasikan validator APK
lama terhadap `rules.json` v3: id literal yang dipakai aturan cuma `bca` dan
`mandiri`, keduanya sudah ada di konstanta kode, jadi v3 diterima.

**`patterns.json`: pola regex notifikasi jadi data.**

`Parser` tadinya punya 5 regex yang dikompilasi sekali sebagai `val`, dan 3
lagi yang dikompilasi ulang tiap pemanggilan. Format notifikasi bank berubah
cukup sering, dan tiap perubahan butuh build di dua HP.

Dua aturan yang tidak boleh dilanggar:

1. **Jalur notifikasi tidak pernah menyentuh jaringan.** Pola ditransfer
   `PatternStore.terapkan`, yang dipanggil dari UI dan saat aplikasi dibuka.
   Setelah itu `PatternStore.aktif()` hanya mengembalikan objek yang sudah
   dikompilasi, persis seperti `val Regex` biasa - jadi tidak ada regresi
   performa di jalur panas. Kalau pola dibaca dari cache lalu dikompilasi ulang
   per notifikasi, itu justru lebih lambat dari sebelumnya.
2. **Pola dari server tidak dipercaya tanpa diuji.** Urutan saringan: compile,
   panjang maksimal 300 karakter, bentuk `(.+)+`, lalu **uji paritas** atas 11
   notifikasi nyata yang tertanam di APK (`PatternStore.PROBE`). Hasil
   ekstraksi harus identik dengan pola bawaan; beda satu karakter pun, pola
   ditolak dan yang bawaan tetap dipakai, alasannya masuk log.

Batasnya harus jujur: **regex dari server tidak bisa dibuktikan aman.**
Tiga langkah di atas adalah mitigation, bukan jaminan. Yang paling menahan
adalah paritas, tapi paritas hanya membuktikan apa yang ada di probe; pola yang
benar di semua probe lalu salah pada format yang belum pernah dilihat tetap bisa
lolos. Penahan terakhir yang berlaku untuk semua bentuk regex adalah
`MAX_TEXT` 2.000 karakter sebelum teks dicocokkan.

**`knownIds`: allowlist id akun jadi data.**

Selama ini menambah satu rekening di bank yang sudah ada butuh build, karena
`KNOWN_IDS` ada di kode dan `RuleValidator` menolaknya. Worse: kegagalan itu
menolak **seluruh** ruleset, jadi sinkron ikut macet. Sekarang
`RuleSet.knownIds` = daftar dari server digabung `KNOWN_IDS` bawaan. Server
boleh menambah, tidak bisa menghapus bawaan. `rules.json` yang belum punya
field itu aman - hasilnya cuma bawaan.

Field ini **wajib dimodelkan**, bukan ditempel sebagai string: dialog Aturan
menyalin JSON lewat `RuleSet`, jadi field yang tidak ada di model akan hilang
diam-diam setiap kali pengguna mengedit manual.

**`isSendable` diperketat.** Sebelumnya `SENDABLE` menerima id lowercase
apa saja - itulah asal akun sampah `tujuan rek` dan `normalisasi` yang ada di
`tele_tabungan`. Sekarang gerbangnya memeriksa bentuk **dan** nama akun, dan
dipakai di ketiga jalur: rules, template belajar, dan usulan AI.

**Bug yang tertangkap saat porting.** `isSendable` versi pertama memeriksa
`groupValues[3]` untuk `/pindahin`, padahal grup 3 itu **nominal**, bukan id
tujuan. Kalau lolos, **semua** perintah `/pindahin` akan ditolak - dan tidak ada
error, hanya pindahin yang diam-diam hilang. Tertangkap karena logikanya
dijalankan ulang di Python atas seluruh fixture, bukan hanya dibaca.

**Peta paket bank yang tadinya terduplikasi tiga kali.** `bankPkg` masuk
`patterns.json`, dan dua salinan lama di `Config` serta `Gemini` dihapus.
Sebelumnya peta itu ada di tiga tempat; pola yang diubah di satu tempat
diam-diam berbeda dari yang lain. `isBankEnabled` juga diubah dari
`else -> false` jadi `true`: kalau tidak, bank yang baru ditambahkan lewat
`patterns.json` tidak akan pernah jalan karena tidak punya tombol di UI.

**Verifikasi.** Tetap tanpa JDK, jadi `Parser`, `Rule`, `RuleEngine`, dan
`PatternStore` diport ke Python lalu 69 pemeriksaan dijalankan - semuanya
lulus, termasuk uji bahwa pola yang sengaja dilonggarkan **ditolak** oleh
paritas. Yang tetap tidak terbukti adalah kompilasi; `./gradlew testDebugUnitTest`
harus jalan di mesin yang punya SDK.

**Yang tidak ikut terperbaiki, dan tidak akan pernah di vidha.** Notif BRImo
bentuk kaya hanya menampakkan tiga digit terakhir, sedangkan peta rekening
memakai empat. Pola regex tidak bisa menutup itu - regex tidak bisa membawa
artinya "ini rekening vidha". Notif itu akan tetap diam. Itu kehilangan
pencatatan, bukan penulisan yang salah, dan memang lebih baik begitu.

### O. Pembulatan `/pindahin` Hanya Antar Bank + Peta Arsitektur Dinamis (2026-09-28)

**Gejala.** Empat `pinout` BRI tidak bulat jadi yatim tanpa `pinin` pasangan
sama sekali: `#33` (1.567.150), `#130` (152.750), `#256` (2.623.400),
`#317` (282.750). Sisa pembagilah ke ribuan: 150, 750, 400, 750.

**Akar.** `telegram.php` menghitung nominal masuk `/pindahin` dengan
`$pindah_in = (int)(floor($pindah_out/1000)*1000)` **tanpa syarat**. Itu benar
untuk satu kasus: transfer lewat Flip, yang nominal keluarnya termasuk fee admin
(selisih 325-502, persis pola fee yang terlihat di 17 data `bca pinout`).
Tapi diterapkan juga ke transfer antar rekening **di bank yang sama**, yang
tidak mungkin punya fee. Akibatnya nominal internal yang tidak bulat kehilangan
sisanya, dan kelima `pinout` yatim tercipta.

**Koreksi diri.** Sebelumnya dinyatakan "Rp 750 hilang tanpa jejak". Itu tidak
tepat: pembulatan itu memang disengaja untuk Flip, dan fee-nya tercatat sebagai
selisih antar sisi. Yang rusak hanya kasus bank sama - yang nominalnya wajib
sama persis.

**Perbaikan** (`telegram.php`, repo `omarifs/telegram`):

```php
$beda_bank = substr($ex[1],0,3) !== substr($ex[2],0,3);
$pindah_in = $beda_bank ? (int)(floor($pindah_out/1000)*1000) : $pindah_out;
```

Tiga karakter pertama cukup: `bri.ibu` dan `bri.vidha` sama-sama `bri`,
sedangkan `bca` dan `mandiri` berbeda. Nama akun dikirim sebagai id.

Diverifikasi dengan simulasi 10 kasus terpisah (tanpa menyentuh DB): semua
lulus, termasuk kasus yang tadinya rusak (`bri.ibu -> bri.vidha 1234567`
sekarang menghasilkan `pinin` 1.234.567 utuh). `php -l` bersih, md5
`b814a20f`, backup `telegram.php.bak-20260928`, deploy ke
`dn-arif:/home/websolusi/telegram/telegram.php`, commit `22d38fc0` di repo
`omarifs/telegram`.

**Batas.** Logika ini membulatkan ke ribuan terdekat, bukan mengurangi fee
sebenarnya. Selama fee di bawah Rp 1.000 - riwayat 325-502 - hasilnya benar.
Fee lebih besar perlu cara lain. Empat `pinout` yatim dibiarkan (keputusan
user): perlu dicek mutasi bank untuk 02 Feb, 04 Apr, 06 Jun, 09 Jul sebelum
memutuskan.

**Arsitektur dinamis - peta lengkap.** Tiga file konfigurasi di server + satu
kode webhook. Yang sampai ke HP tanpa install ulang tergantung APK-nya:

| File server | APK lama (vidha, membeku) | APK baru (arif, 1.0.2) | Cara ditarik |
| :--- | :--- | :--- | :--- |
| `rules.json` | ya | ya | tombol Aturan -> Tarik |
| `patterns.json` | tidak | ya | otomatis saat app dibuka |
| `prompt.txt` | tidak | ya | tombol Pindai AI |
| `telegram.php` | ya | ya | bukan config - ini kode webhook, repo terpisah |

Prinsipnya: yang jadi data adalah string, map, daftar, dan pola. Algoritma
tetap di kode - `pickLast4`, kanonisasi waktu, kunci tanda tangan, gerbang
`isSendable`. `knownIds` sebagai data dengan aturan server boleh menambah tapi
tidak bisa menghapus bawaan.

**Syarat ketat yang dijaga:** setiap file server harus tetap valid untuk APK
yang paling lama. `knownIds` boleh memuat id baru tetapi aturan tidak boleh
memakainya (validator APK lama menolak seluruh file). Setiap perubahan isi
wajib menaikkan `version`, kalau tidak HP diam saja.

### P. Dua Notifikasi Satu Peristiwa Jadi Satu `/pindahin` (2026-09-29)

**Gejala.** Transfer BCA -> BRI lewat Flip menghasilkan dua baris:
`/tabin bri.ibu 3500000` (dari notif BRImo) dan `/tabout bca 3500351` (dari
notif myBCA). Seharusnya satu `/pindahin`. Selisih 351 adalah fee admin.

**Kenapa tidak bisa diperbaiki dari `rules.json` saja.** `/pindahin` butuh kedua
rekening di dalam satu notifikasi, dan tidak ada notifikasi yang punya keduanya:

| Notifikasi | Isi | Yang tidak ada |
| :--- | :--- | :--- |
| BRImo | 3.500.000, rekening 8533 | tidak menyebut `bca`, tidak ada nama pengirim, tidak ada kata "transfer" |
| myBCA | 3.500.351 | tidak ada nomor rekening sama sekali |
| Flip | "Transfer to NURHASANAH successful!" | tidak ada nominal, tidak ada rekening |

`RuleEngine.decide` dipanggil sekali per notifikasi dan tanpa state, dan
kosakata tertutup di `Rule.kt` tidak punya token untuk menggabungkan dua
notifikasi. Sinyal teksnya juga nihil: `Parser.extractName` untuk notif BRI itu
cuma menghasilkan "Sobat" generik.

**Yang DITOLAK: rule BRI `/pindahin bca {dst} {amount}`.** Lolos validator,
tapi akan memakai `bca` sebagai asal untuk *setiap* transfer masuk dari luar.
Persis kelas korupsinya di 5L, di mana `accounts` yang salah membuat transfer
vidha -> ibu terbaca sebagai pemasukan dari pihak luar. Data yang dihitung bukan
hanya bentuk command.

**Perbaikan: `Pairer.kt`.** Hasil klasifikasi ditahan sebentar mencari sisi
pasangnya, lalu dua sisanya disatukan. Syarat pemasangannya semuanya harus
benar: arah berlawanan, kedua id rekening sendiri, prefix bank berbeda,
`in <= out`, `out - in <= feeMax`, dan jarak waktu <= `windowSec`. Syarat "bank
berbeda" itu yang mencegah transfer antar rekening di bank yang sama ikut
terpair - kalau tidak, nominal internal yang tidak bulat jadi ikut dibulatkan,
kerusakan yang sama seperti di 5O.

Jendela waktu dan toleransi fee tetap **data** di `rules.json` (blok `pairs`),
jadi bisa diubah tanpa build. Yang jadi kode hanya algorithma pennies-nya.

**Konsekuensi yang disengaja.** Sisi yang tidak berpasangan dikirim apa adanya
setelah `windowSec` - jadi kegagalan pairing hanya **menunda**, tidak
menghilangkan. Penjadwalan itu wajib, bukan tambahan: tanpa itu, sisi
`tabin` yang sendirian (dana masuk dari orang luar) tertahan selamanya kalau
tidak ada notifikasi lain yang datang, dan uangnya tidak pernah tercatat.

**Batas keras: hanya dua notifikasi yang tiba di HP yang sama.** Transfer dari
HP satu ke rekening di HP lain tidak pernah bertemu - tiap HP punya buffer
sendiri dan tidak ada kanal antar-HP. Saldo di kedua sisi tetap benar karena
masing-masing tetap mengirim `/tabout` dan `/tabin`; yang hilang hanya penanda
bahwa keduanya satu peristiwa. **Tidak ada `rules.json` yang bisa menutup ini**:
HP pengirim tidak pernah tahu nomor rekening tujuan, jadi pasangannya harus
ditebak, dan tebakan di sini merusak angka yang tidak bisa dikembalikan sendiri.

**Empat jebakan yang sudah kena waktu menulis.** Semuanya ketahuan karena
tidak ada kompilasi lokal, hanya pemeriksaan bentuk:

1. **Orphaned brace blok.** Mengedit penjelasan panjang di tengah file
   menyisakan `}` dan `}` yang menggantung di akhir `NotifService.kt` dan
   `Config.kt`. Keduanya kurung kurungnya tidak seimbang. Perhatikan jumlah
   `{`/`}` dengan strip komentar dulu - menghitung mentah salah karena `{` di
   dalam string dan karakter bukan brace.
2. **`Paired` tidak punya `dedupKey`, tapi `NotifService` memakainya.** Field
   itu ada di `Pass` saja. Harus dicek silang: setiap `outcome.X` yang dipakai
   harus benar-benar ada di deklarasi `data class`-nya.
3. **Kunci dedup untuk command pasangan harus dibuat sendiri.** Memakai kunci
   milik salah satu sisi membuat notifikasi sah berikutnya yang kebetulan punya
   kunci sama dianggap duplikat. `Sink.pairKey` menggabungkan dua kunci. Bentuknya
   tetap `ibanknotif:v1:<64 hex>` - kalau tidak, `claim_dedup_key` di
   `telegram.php` mengembalikan false, webhook menjawab "deduplicated" lalu
   **keluar tanpa mencatat apa pun**. Bentuk yang salah berarti transaksi hilang
   diam-diam, jadi bentuknya dikunci test.
4. **`vararg` terpotong jadi `var *` di `Config.markProcessed`.** Commit
   `89b8687` rusak total karena satu baris, dan build gagal dengan 4 error
   yang kelihatan seperti 4 bug berbeda: `Config.kt:92` `'var' on function
   parameter is not allowed`, `Config.kt:97` `Type mismatch: String tapi
   Collection<String> diharapkan`, lalu `MainActivity.kt:174` dan
   `NotifService.kt:138` `spread operator (*foo) may only be applied in vararg
   position`. Ketiga error terakhir **efek cascading**: begitu tanda tangan
   `markProcessed` tidak dikenali sebagai vararg, `*newKeys` di pemanggil
   otomatis gagal - padahal pemanggilnya benar sejak awal.

   Yang paling menyakitkan: baris itu sudah benar di commit sebelumnya,
   jadi bukan salah logika, tapi hasil penyuntingan. Dan **semua pemeriksaan
   yang saya lakukan lolos**: kurung kurawal sudah seimbang, `var` dan `*`
   sama-sama karakter yang sah secara sintaks, dan audit pola tidak
   menangkapnya. Yang menangkap hanya compiler. Pelajaran: tanpa kompilasi,
   "tidak ada error di output saya" bukan bukti, dan pemeriksaan bentuk
   punya kelas buta yang harus selalu dicurigai.

**Verifikasi.** Environment ini tidak punya JDK maupun Android SDK, jadi
`./gradlew` tidak bisa dijalankan. Yang dilakukan: `Pairer.kt` dan `PairConfig`
diport ke Python lalu 15 kasus dijalankan (pasangan benar, urutan terbalik,
sisi luar tidak hilang, bank sama ditolak, fee terlalu besar ditolak, `in > out`
ditolak, di luar jendela ditolak, satu sisi tidak dipakai dua kali, `pairs`
tidak hilang saat round-trip JSON, nilai di luar batas memakai default) -
semua lulus. `RuleValidator.validateAll` atas `rules.json` versi 4 juga lulus.
E2E dengan tiga notifikasi asli: dua baris lama berubah jadi satu
`/pindahin bca bri.ibu 3500351`. **Bentuk, bukan kebenaran kompilasi** -
`./gradlew testDebugUnitTest` tetap harus jalan di mesin yang punya SDK, dan
hasilnya harus dilaporkan apa adanya.

**Belum ditutup.** `Gemini.kt` masih menyuruh AI memakai `bri.ibu` untuk notif
tanpa rekening, dan masih menyebut `3508 = bri.vidha` (5L). AI yang menulis
aturan untuk kasus antar-bank ini akan membuat rule bogus, karena kasus itu memang
tidak bisa diekspresikan sebagai aturan. `prompt.txt` di server perlu
ditegaskan lebih dulu.

### Q. Biaya Admin Transfer Antar HP Terlihat di Rekap (2026-09-29)

Lanjutan 5P, untuk kasus yang Pairer **tidak** bisa tangani: transfer dari HP
satu ke rekening di HP lain. Sisi keluar dan sisi masuk tiba di perangkat
berbeda, jadi tidak pernah bertemu, dan `/pindahin` tidak pernah terbentuk.

**Yang hilang, dan itu bukan saldo.** Sisi `out` dan `in` sama-sama benar, jadi
`Tabungan All Time` tidak berubah. Yang hilang ada di baris rekap
`Pindah Rek ... (Biaya Adm : ...)`: `total_pindahout` dan `total_pindahin`
hanya menghitung `pinout`/`pinin`, dan transfer antar HP tidak pernah jadi
keduanya. Jadi fee admin 351 itu **terpotong dari saldo tapi tidak pernah
tampil sebagai biaya di mana pun** - hilang dari catatan.

**Perbaikannya di `telegram.php`, jalur baca saja.** Di dalam loop `/tabtotal`,
baris `out` dan `in` dikumpulkan ke `$rows_out` dan `$rows_in`. Setelah loop,
pasangan dicari dan **hanya ditampilkan** sebagai baris baru:

```
Transfer antar HP (terdeteksi dari dua baris, tidak diubah): Rp. 3.500.000 (Biaya Adm : 351)
  - bca -> bri.ibu : 3.500.351 (biaya 351)
```

**Kenapa tidak sekalian mengubah barisnya.** Membership yang lebih rapi
(`link_id`, atau mengubah tipe jadi `pinout`/`pinin`) kedengarannya lebih
benar, tapi justru **berbahaya**: memindahkan `in` dan `out` keluar dari
`total_in`/`total_out` akan mengubah angka **Mutasi** dan **Tabungan All Time**,
dan kedua angka itulah yang dipakai untuk melihat saldo. Menyesuaikan
klasifikasi lewat tebakan waktu berarti mengubah angka uang yang sudah benar.
Prinsipnya sama seperti di bagian 7: kalau "diam" salah klasifikasi, yang paling
parah bukan angkanya salah tampil, tapi angkanya berubah tanpa jejak.

Konsekuensinya disengaja: kalau heuristik ini salah tebak, yang salah hanya
satu baris tampilan, dan tidak ada data yang tersentuh. Blok baru tidak
memuat satu pun `INSERT`, `UPDATE`, `DELETE`, `querydb`, atau
`begin_transaction` - sudah diverifikasi.

Syarat pemasangan sama seperti versi app: prefix bank berbeda, tanggal sama,
`in <= out`, dan `out - in <= 1000`. `$taken_in` +
`break` supaya satu baris tidak dipakai dua kali.

**Verifikasi.** Environment ini tidak punya PHP, jadi `php -l` dijalankan di
container `php:8.2-cli` (podman): **No syntax errors detected**. Karena
interpreter-nya benar-benar ada, logika pairing-nya bukan lagi ports
ke bahasa lain - 15 kasus dijalankan dengan PHP sungguhan: pasangan Flip
sungguhan (nominal 3.500.000, fee 351), bank sama ditolak, selisih 500.000
ditolak, dana masuk dari luar tidak terpasang, beda tanggal ditolak, satu
sisi tidak dipakai dua kali, dua transfer terpisah di hari sama (nominal
4.000.000, fee 751), `in > out` ditolak, dan `bca.ayah` -> `bri.ibu`
terdeteksi karena yang dibandingkan prefix bank, bukan nama akun penuh.
Semuanya lulus. Sidinya juga di-render dengan data satu bulan tiruan untuk
memastikan `total_in`/`total_out` tetap 4.250.000/4.000.351 - tidak berubah
satu rupiah pun.

**Kenapa `PAIR_FEE_MAX=1000` ditulis langsung di PHP, bukan diambil dari
`rules.json`.** Nilainya harus sama dengan `feeMax` di app, kalau tidak dua
sisi bisa menampilkan angka fee yang berbeda untuk transfer yang sama. Dua
tempat yang harus disamakan manual adalah harga yang murah sekarang dan
perbedaan nilai fee yang rusak nanti - kalau nanti fee memang berbeda per bank,
barulah dipisah ke config bersama.

**Deploy.** Backup di server `telegram.php.bak-20260929-123525`, lalu scp.
md5 sebelum `b814a20f` (versi 5O, `PAIR_FEE_MAX` = 0) -> sesudah
`59bbf754`. `php -l` di server **No syntax errors detected** (PHP 8.2.32),
`grep -c PAIR_FEE_MAX` = 2, dan `sed -n "/PAIR_FEE_MAX=/,/Mutasi/p" | grep -c
"INSERT\|UPDATE\|DELETE\|querydb"` = **0**. Commit `ca2b7565` di repo
`omarifs/telegram`, sudah dipush ke `main`.

**Uji terhadap data DB asli, read-only.** 400 baris terakhir
(`out=200 in=122 pinout=38 pinin=40`) menghasilkan 4 pasangan:

```
bca  -> bri ibu : 3.500.351 (biaya 351) pada 2026-09-29
bca  -> mandiri : 100.000  (biaya   0) pada 2026-04-06
bri ibu -> bca  : 100.000  (biaya   0) pada 2026-09-22
briibu  -> bca  : 100.000  (biaya 148) pada 2026-09-03
```

Yang pertama **persis transfer Flip dari tangkapan layar 29/09** - terdeteksi,
fee 351, benar. Pasangan bank sama: 0. Skrip uji hanya `SELECT` dan tidak
memanggil `send()`, jadi tidak ada pesan ke Telegram dan tidak ada data yang
berubah. File sementara di server sudah dihapus.

**Dua pasangan berbiaya 0 perlu diwaspadai, dan itu memang keterbatasan yang
sudah dijelaskan di atas.** Nominal bulat 100.000 di dua bank pada hari yang sama
adalah pola yang wajar terjadi tanpa hubungannya juga, jadi dua baris itu bisa
saja pasangan yang salah. Yang mereda: keduanya cuma baris tampilan, saldo
tidak tersentuh. Kalau nanti terlihat mengganggu, persempit syaratnya -
misalnya wajib selisih fee antara 1 dan 1000 supaya nominal pas dihapus dari
kandidat.

**Nama akun di data sudah korup dari sebelumnya** (`bri ibu`, `briibu`,
`brividha`) - itu luka lama dari 5L, bukan efek perubahan ini. Prefix bank-nya
masih terbaca benar, jadi pairing tetap berfungsi, tapi nama yang tampil perlu
dibersihkan terpisah kalau mau rapi.


**Batasnya, jujur.** Dua transfer antar HP dengan selisih nominal di bawah
1.000 pada hari yang sama bisa saling tertukar, dan yang tertukar hanya baris
tampilan. Saldo tidak tersentuh. Menghilangkan itu butuh identitas transaksi
yang dibagi dua perangkat, dan dua perangkat tidak berbagi state.

### R. Nama Rekening Pecah dan Baris Sampah di `tele_tabungan` (2026-09-29)

Temuan dari audit read-only, **bukan** efek perubahan 5P dan 5Q. Kebetulan
audit ini menemukan data yang sudah rusak dari versi app lama.

**Kolom `nama` berisi 8 nilai untuk rekening yang hanya ada 4:**

| `nama` | baris | saldo berjalan |
| :--- | ---: | ---: |
| `bca` | 205 | −10.848.833 |
| `bcaarif` | 3 | +11.490.115 |
| `bri ibu` | 109 | +5.977.906 |
| `briibu` | 27 | +2.824.750 |
| `bri vidha` | 70 | +74.209.300 |
| `brividha` | 42 | −24.020.274 |
| `mandiri` | 17 | +103.488 |
| `normalisasi` | 4 | −410.422 |
| `tujuan rek` | 1 | +26.000 |

Akibatnya saldo per rekening sangat menyesatkan: `bri vidha` tercatat +74 juta
sementara `brividha` −24 juta, padahal itu rekening yang sama.

**Yang diperbaiki: 5 baris sampah** (`normalisasi` ×4, `tujuan rek` ×1) — sisa
`Parser.toCommand` versi lama yang dulu menulis nama akun dari teks mentah.
Query `Tabungan All Time` menjumlahkan semua baris tanpa memfilter nama, jadi 5
baris itu membuat angka yang dilaporkan meleset **−384.422**. Setelah dihapus:
478 → 473 baris, `Tabungan All Time` 57.770.075.

**Yang TIDAK diperbaiki, dan alasannya.** `bri ibu`/`briibu` dan
`bri vidha`/`brividha` jelas rekening yang sama, tapi menggabungkan keduanya
menyentuh saldo rekening nyata dan butuh keputusan: apakah `bcaarif` (3 baris,
+11.490.115) rekening terpisah atau salah ketik? Itu tidak bisa ditebak.
`git log` dan `tele_tabungan` tidak menjawabnya.

**Temuan lebih serius: satu `pinin` yatim.** `pinout` 49 baris, `pinin` 50.
Dua `pinin` 1.000.000 pada 2026-06-13 18:58 (`bri vidha` id 272 dan `bri ibu`
id 273) tidak punya pasangan `pinout` - artinya transfer 1 juta itu tercatat
hanya di sisi penerima. Satu `pinout` 26.000 (id 444, `bri ibu`,
2026-09-06) juga tanpa pasangan, pasangannya baris `tujuan rek` yang baru
dihapus. Kemungkinan besar hilang bukan karena saldo, tapi karena
`/pindahin` lama yang gagal separuh.

**Yang tidak boleh dilakukan tanpa keputusan:** menambah baris `pinout` 1 juta
secara manual. Itu berarti mengarang transaksi, dan seperti biasa, angka yang
dibuat-buat lebih sulit dikoreksi daripada yang hilang.

### S. Outbox: Notifikasi yang Hilang Tanpa Jejak (2026-09-30)

**Gejala.** Transfer Gopay masuk Rp 69.350, notifikasi myBCA terbaca dan
klasifikasinya benar (`/tabin bca 69350`), tapi **tidak ada barisnya di
`tele_tabungan`**. Tidak ada di `tele_notif_dedup` juga.

**Urutan kejadian dari log HP, dibaca kronologis.** `AUTODETECT` → `AUTO-SEND`
→ `TAHAN` → 28 menit → `SERVICE CONNECTED` → `FLUSH` → **tidak ada baris `POST`
maupun `ERR`**. Setelah itu, notifikasi yang sama masih ada di shade tapi
selalu `SKIP (DUPLIKAT)`.

**Akar masalahnya bukan Pairer, tapi urutan dua baris ini:**

```kotlin
Config.markProcessed(this, *newKeys)   // notifikasi ditandai SUDAH diproses
submit(...)                            // baru sesudah itu dikirim
```

Kalau proses mati di antara keduanya, notifikasi hilang **permanen**:

1. `isProcessed` sudah `true`, jadi `scanActiveNotifications()` menyingkirkan
   di setiap catch-up berikutnya
2. `dedup_key` sudah diklaim di server, jadi kirim ulang dijawab
   `deduplicated` lalu `exit()` tanpa `INSERT`

Bug ini **bawaan**, bukan hasil refactor - kode lama juga `markProcessed`
sebelum `Sink.send`. Yang dilakukan Pairer cuma memperbesar jendelanya: dari
**mikrodetik** (enqueue → worker → POST dalam ~500ms) jadi **28 menit**,
sehingga jendela kematian itu jadi besar. Terbukti di DB: `tabin bca` terakhir
yang masuk 28/09 22:17:28, lalu tidak ada sama sekali - padahal sebelumnya
rutin tiap 1-2 hari.

**Bukti bahwa `claim_dedup_key` bukan penyebabnya.** Fungsi itu diuji langsung
di server: kunci baru selalu `TRUE: diklaim`, kunci sama `affected_rows=0`,
kunci ditolak `FALSE: format kunci`, teks bukan command `FALSE: format teks`.
Regex-nya juga benar (64 hex lolos, 63/65 hex dan huruf besar ditolak). Fungsi
ini **sehat** - jangan disalahkan sebagai penyebab.

**Perbaikan (`NotifService.kt` + `Config.kt`, versi 1.0.4).** Urutan dibalik:
tulis command ke **outbox** disk dulu, baru `markProcessed`, baru kirim. Yang
tersisa di outbox dikirim ulang setiap aplikasi dibuka. `Sink` antreannya
memang in-memory, jadi tanpa outbox tidak ada tempat lain untuk menyimpan
command yang belum tentu sampai.

**Tambahan di `Sink.kt`.** Dua hal yang membuat kehilangan tidak terlihat:

1. **Baca response body.** `POST → 200` selama ini menipu: `telegram.php`
   menjawab `{"deduplicated":true}` dengan HTTP 200 lalu keluar tanpa
   menyimpan apa pun. Sekarang dibaca dan dicatat `DITOLAK-DEDUP`.
2. **Retry** dengan jeda, karena jaringan HP sering putus sesaat.

**Yang tidak diperbaiki, dan alasannya.** `claim_dedup_key` masih keluar
dengan `exit()` saat kunci sudah ada. Itu **benar** - kunci sama berarti notif
sama, dan mencatatnya lagi akan menghasilkan dobel catat. Yang berubah cuma
visibilitasnya: sekarang terlihat di log HP, bukan hilang diam-diam.

### T. Setelan Pengiriman dan Housekeeping Jadi Data (2026-09-30)

Lanjutan 5P dan 5Q: "yang jadi data adalah string, map, daftar" sekarang
mencakup perilaku pengiriman, bukan cuma klasifikasi.

`rules.json` dapat dua blok, keduanya default-nya **persis sama** dengan
angka yang dulu tertanam di kode, jadi `rules.json` tanpa blok ini berperilaku
seperti sebelumnya:

```json
"delivery": { "maxRetries": 3, "retryDelayMs": 1500, "timeoutMs": 10000, "sendGapMs": 500 },
"runtime":  { "logLines": 100, "maxProcessedKeys": 200 }
```

| Setting | Menggantikan | Batas atas |
| :--- | :--- | :--- |
| `delivery.maxRetries` | jumlah percobaan kirim | 10 |
| `delivery.retryDelayMs` | jeda antar percobaan | 60.000 |
| `delivery.timeoutMs` | timeout koneksi & baca | 60.000 |
| `delivery.sendGapMs` | jeda antar POST | 60.000 |
| `runtime.logLines` | jumlah baris log | 1.000 |
| `runtime.maxProcessedKeys` | jumlah kunci anti-duplikat | 2.000 |

Angka di luar batas **diabaikan dan diganti default**, bukan dipakai -
`maxRetries: 99999` akan membuat aplikasi tidak bisa dihentikan. Pola `bound()`
yang sama sudah dipakai `PairConfig` sejak 5P.

**Yang sengaja TIDAK dijadikan data:** `MAX_TEXT` (2.000) dan `MAX_PATTERN`
(300) di `PatternStore`, plus seluruh algoritme. Batas itu pengaman - mengubahnya
dari server hanya membuka pintu ke regex yang bisa macet atau ke teks
yang terlalu panjang. Sama seperti `knownIds` di 5N: daftar boleh ditambah,
aturan tidak boleh memakainya.

**Aman untuk APK tertua.** `RuleSet.fromJson` hanya membaca `version`,
`accounts`, `knownIds`, `rules`; `validateAll` hanya iterasi `rules`. Kunci
asing `pairs`, `delivery`, `runtime` **diabaikan, bukan error** - disimulasikan
terhadap `rules.json` v6: 14 aturan utuh, id literal tetap `bca` + `mandiri`,
`accounts` dan `knownIds` tidak berubah. HP vidha tidak membeku.

**Verifikasi.** Alur outbox disimulasikan ulang di Python (22 pemeriksaan,
semua lulus) termasuk skenario yang persis membuat Gopay hilang:
proses mati setelah enqueue → outbox masih menyimpan → app dibuka → kirim
ulang → **MASUK**. Selain itu `DeliveryConfig`/`RuntimeConfig` disimulasikan
(21 pemeriksaan): default, nilai dari server, angka mustahil ditolak,
round-trip `toJson`/`fromJson` stabil, dan file v6 diterima validator APK lama.
**Bentuk, bukan kebenaran kompilasi** - `./gradlew testDebugUnitTest` wajib
jalan di mesin yang punya SDK.

**Langkah WAJIB setelah memasang 1.0.4, urut dan tidak boleh dilewat.**

Template hasil belajar dievaluasi **lebih dulu** daripada aturan:

```
NotifService.kt:114  Learning.match(bank, fullText, ...)   <- lebih dulu
NotifService.kt:129  RuleEngine.decide(parsed, rules)      <- belakangan
```

Kalau `learned_templates` masih berisi template dari sebelum 1.0.4, template
itu **menang** dan seluruh perbaikan di 5S tidak akan terlihat sama sekali -
gejalanya `AUTO-SEND (LEARNED): ...` di log, bukan `AUTO-SEND [nama-aturan]`.
Jadi:

1. Tekan **Bersihkan Hasil Belajar** di **kedua HP**. Bukan opsional, dan
   tidak bisa dicicil: template yang sudah ada di HP Vidha pun masih aktif
   di sana.
2. Buka aplikasi setelah itu, supaya `rules.json` v6 ikut ter-tarik
   ( otomatis saat app dibuka).
3. Baru tes satu transfer dan baca lognya. Kalau baris pertama yang muncul
   masih `AUTO-SEND (LEARNED)`, berarti langkah 1 belum benar-benar
   membersihkan.

Kalau ternyata ada template yang memang sengaja disimpan - misalnya hasil
koreksi manual yang tidak bisa diekspresikan sebagai aturan - jangan dihapus
begitu saja. Catat dulu isinya, karena setelah dihapus tidak ada tempat lain
untuk menuliskannya.

### U. Model AI Dicabut Google, Jadi Model AI Jadi Data (2026-10-01)

**Gejala.** Tombol **Pindai AI** dan **Perbaiki dengan AI** mati. Di log:

```
GEMINI ERR 404: {"error":{"code":404,
"message":"models/gemini-2.0-flash is not found for API version v1beta, or is
not supported for generateContent. Call ListModels to see the list of available
models and their supported methods."}}
```

**Bukan salah klasifikasi, dan bukan salah kode.** `Gemini.kt` masih pinning
`private const val MODEL = "gemini-2.0-flash"`. Google mencabut model tanpa
pemberitahuan, jadi setiap pencabutan memaksa build ulang - dan build sudah
gagal berulang kali di repo ini (5K). Jalur auto-detect offline tidak ikut
mati: itu `NotifService` + `RuleSet`, tidak menyentuh Gemini sama sekali.

**Perbaikannya: `ai.model` jadi data di `rules.json` v7.** Bentuknya sama
dengan `pairs`, `delivery`, dan `runtime` - konstanta di dalam data class,
defaultnya di dalam kode, parser-nya menolak nilai di luar batas:

```kotlin
data class AiConfig(val model: String = DEFAULT_MODEL)
```

`AiConfig` berisi `DEFAULT_MODEL = "gemini-flash-latest"` dan
`MODEL_RE = ^[a-z0-9][a-z0-9._-]{0,63}$`. Defaultnya **alias**, bukan nomor
versi, dan itu justru inti perbaikannya: `gemini-2.0-flash` mati justru karena
nomor versinya dipatok. Alias selalu menunjuk flash stabil terbaru, jadi tidak
perlu dijaga setiap kali Google menaikkan major.

**Jebakan yang ketahuan saat menulis test: `optString()` MENG-COERCE.** Ini
bukan tebakan, hasil uji:

```
{"model": null}  ->  optString = "null"  ->  LOLOS regex  ->  URL jadi
                                    .../models/null:generateContent
{"model": 123}   ->  optString = "123"   ->  ditolak regex -> default
```

`"null"` lolos `MODEL_RE` karena memang sah bentuknya. Perbaikannya nilai
harus dipastikan `String` dulu sebelum diuji:

```kotlin
val m = (o.opt("model") as? String)?.trim().orEmpty()
```

Ini kelas bug yang sama seperti `PatternStore`: input dari server tidak
boleh dipercaya tanpa saringan, dan `null` adalah input yang sah di JSON.

**Kenapa `ai` harus ikut di `toJson` juga.** Kalau hanya di `fromJson`, blok itu
hilang diam-diam setiap kali pengguna mengedit Aturan di HP, karena dialog itu
menyalin JSON lewat `RuleSet.toJson` lalu `fromJson`. Model yang sudah
diperbaiki di server akan kembali ke default tanpa pesan apa pun. Persis
jebakan yang sudah tercatat di `Rule.kt:356` dan `KnownIdsTest`.

**Yang TIDAK bisa diperbaiki lewat data: HP yang masih pakai APK lama.** APK
lama mengabaikan kunci `ai` karena tidak punya `fromJson`-nya, jadi ia akan
terus memanggil `gemini-2.0-flash` yang sudah dicabut. Tidak ada `rules.json`
yang bisa menutup ini - sama seperti 5P. APK lama **tidak ditolak** (sudah
disimulasikan terhadap parser versi lama: 14 aturan, semua token `when` dan
id akun dikenal), jadi file server tidak membekukan HP lama. Tapi Pindai AI di
HP lama tetap 404 sampai APK-nya diganti.

**`v1beta` dibiarkan.** Fokusnya satu penyebab yang ditunjukkan bukti: nama
model. Kalau `gemini-flash-latest` ternyata juga 404 setelah ganti APK,
penyebab berikutnya adalah versi API (`v1` vs `v1beta`), dan itu bukan
konten data - perlu keputusan tersendiri.

**Verifikasi.** Environment ini tidak punya JDK, jadi `./gradlew` tidak jalan.
Yang dilakukan: port `AiConfig.fromJson`/`toJson` ke Python lalu 11 kelompok
kasus dijalankan (tanpa blok, nilai server dipakai apa adanya, 7 bentuk salah
ditolak, 5 bentuk sah diterima, nilai non-String ditolak, putar-balik utuh,
model bertahan setelah diedit, file APK lama aman, `assets/rules.json` benar,
URL akhir, model mati tidak ada di URL) - semua lulus. Plus pemeriksaan bentuk
(kurung kurawal setelah string dan komentar dibuang, termasuk raw string
`"""`), scan korup, dan cross-check anggota `AiConfig.x` yang dipanggil. Shape
saja, **bukan bukti kompilasi** - `./gradlew testDebugUnitTest` harus jalan di
mesin yang punya SDK dan hasilnya dilaporkan apa adanya.

Deploy: backup `rules.json.bak-20261001-230451` di `dn-arif`, lalu scp v7.
Curl ke `https://tools.websolusi.com/telegram/rules.json` sudah mengembalikan
`ai.model = gemini-flash-latest` dan `version = 7`.

**Langkah di HP setelah memasang 1.0.5.** Tekan **Tarik Semua** (atau buka
saja aplikasinya, penarikan otomatis), lalu tes Pindai AI dan baca lognya.
Kalau baris pertamanya masih `GEMINI ERR 404 [model ...]`, nama model itu yang
salah - dan perbaikannya cukup ubah satu string di `rules.json` server, bukan
build. **Tidak perlu** tekan Bersihkan Hasil Belajar untuk perubahan ini,
karena tidak ada aturan klasifikasi yang berubah; langkah itu tetap wajib
untuk setiap perubahan isi `rules` (lihat 5S).

**Yang belum terverifikasi, terus terang.** `gemini-flash-latest` berasal dari
dokumentasi, bukan dari respons API: `ai.google.dev` timeout dari sini dan
`ListModels` membalas 403 tanpa API key (`"Method doesn't allow unregistered
callers"`), sedangkan key-nya ada di HP, bukan di repo. Kalau ternyata salah,
log baris baru `GEMINI ERR $code [model $model]:` langsung menyebut model yang
ditolak, dan itu justru bukti bahwa pencatatan model itu berguna.

### V. Situs `websolusi.com`: Auto-Publish Artikel AI (2026-10-03)

**Catatan cakupan.** Subsection ini berbeda codebase dari 5A-5U. Yang dikerjakan
adalah situs publik `websolusi.com` di akun cPanel yang sama (`/home/solveitm/`),
bukan aplikasi Android IBankNotif. Bedanya penting: situs ini **tidak punya clone
lokal** dan **tidak punya shell interaktif** ke server, jadi semua verifikasi
dilakukan lewat HTTP dan file manager. Skill untuk pekerjaan ini:
`.opencode/skills/agc-berbasis-korpus/SKILL.md`.

**Empat bug yang tidak berteriak.** Semuanya statusnya "hijau" di mata sampai
dibuka satu per satu:

1. **`receiver.php` menulis file sembarang tanpa auth.** File PHP yang menerima
   POST dan menulis ke disk tanpa satu pun pemeriksaan identitas. Dihapus, bukan
   diamankan - tidak ada fitur yang perlu dilayaninya.
2. **`parseInt` pada slug artikel.** `parseInt("10-pengenalan-ci-cd...")`
   mengembalikan `10`, bukan `NaN`, tanpa error. Efeknya 5 dari 9 URL artikel 404
   tanpa jejak di log, sementara ID-nya tampak "valid". Rute harus
   `WHERE slug = ?`. Ditambah unique index `slug_unique`.
3. **Hardcoded admin.** `admin/admin123` dan `JWT_SECRET="your-secret-key"` ada di
   kode. Dipindah ke `.env`, kredensial lama dirotasi, lalu **diuji terhadap
   layanannya**: login lama membalas 401. Mengubah file tidak menegakkan nilai
   lama - ini pola yang sama seperti rotasi Telegram di 7.13.
4. **Build gagal diam-diam sejak Agustus.** `admin.tsx` rusak
   (`Unexpected end of file`) dan cron build tidak menulis log, jadi kegagalannya
   hanya sampai ke email cron. Diperbaiki, dan sekarang `build.log` di docroot -
   tanpa itu, build gagal berikutnya juga tidak terlihat.

**Draft bocor ke publik.** `/api/articles` mengembalikan semua artikel termasuk
draft. Sekarang publik hanya melihat `published`, admin lewat `/api/admin/articles`.
Draft yang bocor bukan masalah kosmetik: artikel placeholder ikut terindeks.

**SSR artikel.** Dulu URL artikel mengembalikan shell SPA 10.302 byte berisi
`<head>` kosong - crawler tidak melihat apa pun. Sekarang HTML asli berisi
`<title>`, meta, canonical, Open Graph, dan JSON-LD `TechArticle`,plus
`robots.txt`, sitemap dinamis, dan `og-image.png` (sebelumnya mengembalikan
HTML untuk file PNG).

**Auto-publish, dan syaratnya.** Permintaan pengguna: AGC menerbitkan sendiri,
bukan menumpuk draft. Disetujui **karena sumbernya milik sendiri**: 9 file
`content-corpus/*.md` berisi data operasional akun ini - batas LVE terukur,
isi log error asli, konfigurasi nyata. AI tidak mengarang angka; ia merangkai
fakta yang sudah ada. Kalau korpusnya internet umum, permintaan yang sama harus
ditolak: auto-publish mempublikasikan kemungkinan-kesalahan atas nama perusahaan.

Rantai gerbangnya tiga anak tangga dan tidak boleh ada keempat: sumber (korpus),
prompt (larangan mengarang), validator (panjang, jumlah heading, frasa terlarang,
markdown yang didukung renderer). **Validator wajib jalan sebelum `UPDATE`**,
dan kegagalan harus jadi log.

**Kenyataan:auto-publish tidak menghapus review, ia menggesernya** - dari
sebelum terbit ke sebelum generate.

**Yang sudah jadi bug laporan, bukan REQUEST kedua:**

- SQL sudah `status = 'published'` tapi komentar kepala file masih "writes ...
  DRAFT". Komentar yang menyimpang lebih berbahaya dari tidak ada komentar.
- `PUT /api/articles/:id` adalah **ganti objek penuh**, bukan PATCH.
  `{"status":"published"}` saja menghasilkan HTTP 500 karena kolom lain jadi
  `NULL`. Solusinya: ambil objek, ubah field, kirim utuh.
- Menghapus script sementara **tidak menghapus cron-nya**. Dua cron (tiap 9 menit)
  masih menunjuk `check-gemini.sh` dan `agc-test.sh` yang sudah dihapus - akan
  error terus-menerus tanpa jejak di situs. Cron ikut dibersihkan.

**Idempoten adalah syarat retry, bukan keangkuhan.** AGC melompat artikel yang
kontennya sudah terisi (placeholder = <300 karakter atau diakhiri `...`),
`--force` untuk regenerasi disengaja. Tanpa ini, cron malam bisa menimpa artikel
yang sudah disunting manusia. Placeholder dideteksi dari isi, bukan
`content = ''`, karena artikel hasil suntingan manusia bisa saja pendek.

**Kuota free tier adalah realitiescheduling, bukan masalah kode.** Gemini free
tier 20 request/jam; 9 artikel butuh 9 request. Setelah kuota habis muncul 429
`RESOURCE_EXHAUSTED` lalu 503 high demand. Respons yang benar: **jangan retry di
dalam proses**, biarkan cron berikutnya memproses sisanya. Cron terjadwal 04:23
dan 16:23, jadi isi ulang tercepat ~12 jam - itu yang harus dijanjikan ke
pengguna, bukan "nanti saja".

**Nomor model jadi `.env`, sama seperti di APK.** `GEMINI_MODEL=gemini-2.5-flash`
di `.env`, bukan dipatok di skrip - aturan 5U berlaku persis di dua tempat.
Model sempat `gemini-3.8-flash` (ada di `ListModels`) tapi lebih sering kena 503,
jadi pindah ke `gemini-2.5-flash`.

**Cara verifikasi tanpa shell (tiga lapis, semuanya terpakai):**

1. Sapu 13 endpoint dengan `curl`: kode HTTP, `Content-Type`, dan isi yang
   membuktikan bukan placeholder. Termasuk sisi negatif - draft 404, admin tanpa
   token 401, API tidak cocok JSON 404 bukan HTML.
2. **Uji SSR dengan publish lalu rollback**: artikel id 8 diubah jadi published,
   HTML-nya dicek, lalu dikembalikan ke draft. Tidak di-rollback berarti
   mempublikasikan artikel uji tanpa sengaja.
3. `node --check` untuk skrip, lewat cron sekali jalan.

**Status saat ini.** 2 artikel published dan live (id 2 dan 9, 8.419 dan 8.637
karakter, keduanya masuk sitemap). 7 sisanya masih placeholder dan akan terisi
otomatis oleh cron-agc. Semua artefak verifikasi sudah dihapus, termasuk dua
cron Sementara yang menyinggung.

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

**Lapisan ketiga yang paling sering luput: template hasil belajar.**
`Learning.match` dievaluasi **sebelum** `RuleEngine.decide`
(`NotifService.kt:114` vs `:129`), jadi template yang tersimpan -
dari klasifikasi yang sudah usang - **menang atas** aturan yang baru
diperbaiki. Gejalanya mudah dilihat: di log, barisnya `AUTO-SEND (LEARNED): ...`
bukan `AUTO-SEND [nama-aturan]: ...`.

Aturan main: **setiap kali data di server berubah, wajib tekan "Bersihkan
Hasil Belajar"** di setiap HP yang memasang aplikasi. Bukan opsional, dan
tidak bisa dicicil. Kalau Learning dievaluasi pertama, perbaiki klasifikasi
berarti sia-sia selama template lama masih ada.

Baris `AUTO-SEND (LEARNED)` di log adalah penanda yang harus dicek setelah
setiap perubahan data: kalau masih muncul, perbaikannya belum masuk.

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
