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
  * Server Endpoint Webhook (Public): `https://tools.websolusi.com/telegram/telegram.php`

---

## 3. Struktur Proyek Android

```text
/mnt/a/laragon/www/ibanknotif/
├── .github/workflows/
│   └── build.yml               # Workflow GitHub Actions untuk auto-build APK Debug
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml # Izin Notifikasi & Foreground Service
│   │   ├── java/com/websolusi/ibanknotif/
│   │   │   ├── Config.kt       # Pengaturan SharedPreferences & Log lokal
│   │   │   ├── MainActivity.kt # Tampilan UI Pengaturan & Izin
│   │   │   ├── NotifService.kt # Foreground Listener Service notifikasi bank 24/7
│   │   │   ├── Parser.kt       # Parser regex nominal (Rp/IDR), arah (in/out/pindahin)
│   │   │   └── Sink.kt         # Mengirim payload HTTP POST JSON ke endpoint PHP
│   │   └── res/layout/activity_main.xml
└── README.md                   # Catatan Dokumentasi
```

---

## 4. Cara Melanjutkan Pengembangan (Workflow)

Untuk menambah fitur atau perbaikan di masa mendatang:

1. Masuk ke direktori:
   ```bash
   cd /mnt/a/laragon/www/ibanknotif
   ```
2. Lakukan perubahan kode.
3. Commit dan push ke GitHub:
   ```bash
   git add .
   git commit -m "pesan perubahan"
   git push
   ```
4. GitHub Actions akan otomatis melakukan build APK. File APK dapat diunduh pada tab **Actions → Artifacts (`ibanknotif-debug`)**.
