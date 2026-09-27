---
name: klasifikasi-jadi-data
description: Use when a rule-based decision keeps needing manual correction and the user refuses to rebuild - "jangan build ulang", "fix tanpa build", "custom prompt tidak cukup", "aturannya salah terus", "AI tidak bisa ubah kode", "pindahin salah", "tabin tabout salah". Covers IBankNotif's RuleEngine, data-driven rule sets, closed predicate vocabulary, refusing instead of guessing fallbacks, and validating AI-authored rules. Also use when changing Parser.kt, Rule.kt, RuleEngine.kt, RuleStore.kt, or assets/rules.json.
---

# Keputusan yang berulang salah harus jadi data, bukan kode

Skill ini khusus repo IBankNotif, tapi bagian generiknya berlaku di mana saja.
Detail sejarah dan tanggal ada di `LOG-PROYEK.md` bagian 5J.

## Kapan pola ini tepat

Gunakan kalau keputusan di aplikasi terus salah dan tiap koreksi butuh build
ulang. Sebelum ke sini, cek tangga ini dan berhenti di anak tangga pertama
yang masih berlaku.

1. **Keputusannya benar-benar sering salah?** Kalau jarang, tulis ulang
   kodenya saja. Refactor tidak sepadan.
2. **AI tidak bisa mengubah kode yang sudah dikompilasi.** Ini batas keras.
   Kalau diminta "AI yang perbaiki", jangan mengulang "tapi perlu build" -
   itu hanya mengulang kendala yang sama. Jawab dengan cara yang membuat
   perbaikan mungkin: pindahkan keputusan ke data.
3. **Yang bisa dihilangkan build-nya: pindahkan keputusan ke data.** Ini
   inti playbook.
4. Kalau tidak bisa jadi data, baru perbaiki kodenya.

Yang **tidak** hilang dari kebutuhan build: bug struktural, notifikasi yang
memang tidak sampai (masalah izin Android), UI baru, dependensi baru, kode
native. Yang hilang hanya kebutuhan **memperbaiki klasifikasi**.

## Peta file di repo ini

| File | Isi | Boleh diubah user? |
| :--- | :--- | :--- |
| `Parser.kt` | ekstraksi murni: nominal, rekening asal, rekening tujuan, waktu event | tidak |
| `Rule.kt` | model aturan, kosakata `when` tertutup, validator | tidak |
| `RuleEngine.kt` | evaluasi aturan jadi command | tidak |
| `RuleStore.kt` | sumber aturan (lokal, server, assets), riwayat versi | tidak |
| `app/src/main/assets/rules.json` | **13 aturan bawaan** | **ya, ini inti** |
| `Gemini.kt` | `suggestRule()` mengusulkan aturan, divalidasi sebelum dipakai | tidak |

Tidak ada `if` yang memutuskan arah transaksi di `Parser.kt` lagi. Kalau kamu
menemukan satu, itu regresi.

## Bagi tiga lapis dengan tegas

Mencampur ketiganya adalah penyebab refactor ini sulit.

| Lapis | Isi | Boleh diubah user? |
| :--- | :--- | :--- |
| **Ekstraksi** | regex primitif: nominal, nomor rekening, waktu event | tidak, tapi jarang salah |
| **Keputusan** | arah transaksi, pemetaan akun, bentuk output | **ya, ini inti** |
| **Pengiriman** | antrean, retry, format request | tidak |

Ekstraksi tetap di kode. Yang jadi data adalah lapis keputusan. Pembagian ini
yang membuat 90 baris `if` berubah jadi 13 entri JSON.

## Kosakata tertutup, bukan ekspresi bebas

Aturan memakai daftar token tetap dengan operator AND. OR ditulis sebagai
satu token bergaya pipe. **Jangan** pakai evaluator ekspresi, jangan `eval`,
jangan regex di dalam `when`.

```
jangan:  when: "src in accounts && text =~ /dari/"
boleh :  when: ["src_own", "has:transfer"]
boleh :  when: ["has_any:shopeepay|gopay|ovo", "has_any:top up|isi saldo"]
```

Vocabulary yang menutup hampir semua kasus:

- rekening: `src_own`, `dst_own`, `src_external`, `dst_external`,
  `src_ne_dst`, `src_eq_dst`, `src_known`, `dst_known`
- teks: `has:kata`, `not:kata`, `has_any:a|b|c`, `has_none:a|b|c`
- nominal: `amount_gt:N`, `amount_lt:N`

Kalau sebuah kasus butuh kondisi di luar vocabulary, itu tanda
requirement-nya belum pahami, bukan tanda vocabulary-nya kurang.

## Validator yang menolak aturan tidak aman

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

## Placeholder, bukan string template bebas

```json
{ "when": ["src_own", "dst_own", "src_ne_dst"],
  "command": "/pindahin {src} {dst} {amount}" }
```

`{src}` `{dst}` `{id}` `{amount}` - daftar tetap. Kalau `command` memakai
`{id}` tapi rekening tidak terbaca, pakai `defaultId` yang declared di aturan
itu. Kalau tetap tidak terpenuhi, aturan tidak_match dan evaluasi lanjut ke
aturan berikutnya.

## Kalau tidak tahu, lebih baik diam daripada menebak

Ini bagian yang paling sering jadi bug. Fallback seperti
`else -> "akunCadangan"` diam-diam menulis data yang salah, dan kalau
inputnya uang, itu merusak angka yang tidak bisa dikembalikan sendiri.
`Parser.toCommand` yang lama punya `else -> "bri.vidha"` dan itulah yang
membuat self-transfer dan history korup.

```
pindahin hanya kalau src dan dst keduanya rekening sendiri dan berbeda
hanya satu sisi terbaca  -> jangan jadi tabin/tabout, biarkan pemanggil memutuskan
src == dst              -> jangan catat apa pun
```

Aturan dengan `stop: true` menghentikan evaluasi tanpa mengirim. Pakai untuk
kasus yang harus diam supaya notifikasi pasangannya yang mencatat. Kalau
"diam" punya makna bisnis, jadikan itu property aturan, bukan kondisi yang
kebetulan tidak cocok.

## Anti-duplikat di dua lapis

Satu peristiwa bisa jadi beberapa notifikasi.

1. **Tanda tangan transaksi** dari data **di dalam** teks, bukan waktu
   server. Untuk notifikasi BRI, waktu event ada di badan notif.
   `bank|amount|eventTimestamp`. Dua notif satu peristiwa punya tanda tangan
   sama, jadi yang kedua tertahan.
2. **Deduplikasi per notifikasi** - biasanya sudah ada, tapi hilang saat app
   dipasang ulang karena disimpan lokal.

Tanda tangan harus dihitung **sebelum** jalur mana pun, supaya jalur lain
tidak bisa mem-bypass-nya.

## Perhatikan async saat menandai "sudah diproses"

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

## Fungsi di jalur notifikasi tidak boleh menyentuh jaringan

Kalau `load()` atau `evaluate()` dipanggil dari dalam
`NotificationListenerService`, satu panggilan HTTP di sana akan menahan
pemrosesan notifikasi. Tarikan dari server harus dipisah, dipanggil hanya
dari UI atau startup.

## Urutan aman saat memindahkan keputusan ke data

Jangan langsung mengganti. Bertahap, dan simpan test paritas.

1. Tulis engine + data default **di samping** logika lama
2. Jalankan keduanya pada input yang sama, bandingkan hasilnya
3. Kalau 100% sama pada seluruh sampel, baru hapus yang lama
4. Uji validasi dengan aturan bermasalah, pastikan ditolak

Langkah 2 yang membuat refactor ini aman. Tanpanya, refactor berjalan dengan
asumsi, dan asumsi itu yang paling mahal saat datanya uang.

## AI menulis aturan, bukan output mentah

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

## Baca sampel input nyata sebelum menulis aturan

Jangan menulis regex dari deskripsi. Baca teks notifikasi sungguhan, lalu
normalisasi angkanya untuk melihat berapa banyak skeleton berbeda. Kalau 9
notif cuma jadi 3-5 skeleton, daftarnya memang tertutup. Kalau jadi 9, ada
variasi yang belum dipahami.

Skeleton itu dipakai sebagai fixture test. Bukan contoh karangan - contoh
karangan tidak pernah menangkap variasi yang sebenarnya terjadi. Contoh notif
nyata ada di `app/src/test/java/com/websolusi/ibanknotif/ParserTest.kt`.

## Jebakan yang sudah pernah terjadi di repo ini

- **Jangan pakai `when` sebagai nama property.** Ini yang pernah membuat satu
  baris gagal dan arrast 4 file lain jadi error. Kalau pakai nama field JSON
  yang sama dengan keyword Kotlin, pakai backtick **atau** ganti nama
  property-nya dan biarkan kunci JSON tetap. Yang dipakai di sini: property
  `conditions`, kunci JSON tetap `"when"` karena itu kontrak dengan Gemini.
- **`RuleSet` tidak punya `entries`.** Field-nya `accounts`. Sudah salah
  dua kali di file berbeda pada sesi yang sama. Setelah memperbaiki pola
  seperti ini, selalu `grep` seluruh project - jangan hanya di lokasi yang
  dilaporkan.
- **String literal harus menutup di baris yang sama.** Baris yang berakhir
  dengan `\"` membuat kutipan ter-escape dan baris berikutnya jadi kelanjutan.
  Kalau menulis lewat heredoc, cek baris yang berakhir dengan backslash.
- **Cek attribute XML dari daftar yang benar.** `android:spellCheckLanguage`
  tidak ada, dan `inputType` butuh `textNoSuggestions`, bukan `noSuggestions`.
  AAPT hanya menangkap ini, tidak ada pemeriksaan lokal yang bisa dipakai.
- `.gitignore` hanya berlaku untuk file yang **belum** ter-track. Kalau file
  pernah ter-commit, `git add .` tetap mengambilnya. Perbaikannya
  `git rm --cached`, bukan `git rm`.
- Jangan log string rahasia, termasuk pesan exception. `HttpURLConnection`
  bisa memuat URL lengkap beserta query string, dan itu sempat bocor ke
  screenshot.
- `RuleStore.load()` dipanggil dari listener notifikasi. Kalau ditambah
  panggilan jaringan di situ, notifikasi bank ikut tertahan.
- **Balance kurung kurawal bukan verifikasi kompilasi.** Environment ini
  tidak punya JDK maupun Android SDK, jadi `./gradlew` tidak bisa dijalankan
  di sini. Pemeriksaan statis hanya membuktikan bentuk. Jauh lebih baik verifikasi lewat build di
  mesin yang punya SDK daripada menyatakan "sudah terverifikasi" hanya
  karena kurungnya seimbang.
