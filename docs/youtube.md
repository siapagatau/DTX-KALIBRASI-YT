# Mode YouTube (v1.15, diperbarui v1.16)

Putar musik langsung dari YouTube di dalam app (tanpa unduh lagu), tanpa iklan, dengan slider menit + volume
musik yang ikut mengendalikannya, dan ikut terekam bareng suara kendang.

## Cara pakai
- Tombol **ikon video** di tab kanan (di antara mode mixer dan Kit) - cara kerjanya sama seperti mode edit/mixer/kit:
  tekan = area pad diganti layar YouTube, tekan lagi (atau tombol TUTUP) = kembali ke pad.
- Pintu kedua: ikon video di dialog **Pilih Lagu** (di samping ikon folder).
- Pilih/putar lagu di YouTube, lalu TUTUP mode: musik tetap jalan di belakang pad. Panel musik kanan
  (play/stop, slider menit, slider MUSIK) otomatis mengendalikan YouTube selama YouTube yang terakhir dimainkan.
  Memilih lagu lokal (GANTI/LAGU) atau memutarnya mengembalikan panel ke lagu lokal dan menjeda YouTube
  (dan sebaliknya) - tidak pernah dua musik sekaligus.

## Kenapa tidak lagi "klik play lalu berhenti"
| Penyebab umum | Penanganan |
|---|---|
| WebView dilepas/dibuat ulang saat mode ditutup atau layar direkomposisi | Satu WebView permanen (`YouTubeState`), dikomposisi di satu posisi tetap (`YouTubeLayer`); ditutup = digeser ke luar layar, bukan dihapus |
| Halaman dianggap "tersembunyi" -> YouTube menjeda | `document.hidden/visibilityState` dipalsukan + event `visibilitychange` dibuang |
| Autoplay perlu gestur | `mediaPlaybackRequiresUserGesture = false` |
| Activity dibuat ulang (keyboard/uiMode/density berubah) | `android:configChanges` di AndroidManifest |
| "Video dijeda. Lanjut menonton?" | `window._lact` diperbarui + dialog dikonfirmasi otomatis |
| Pelompat iklan ikut menyentuh video biasa | Hanya bertindak bila pemutar menandai `ad-showing`; video biasa tidak pernah di-seek |

App ke background (`onStop`) menjeda YouTube (tidak dilanjutkan otomatis). Dialog sistem (izin, rekam layar,
file picker) hanya memicu `onPause` dan TIDAK menjeda musik.

## Varian build: Play Store vs Mod (v1.16)
Memblokir iklan YouTube melanggar Ketentuan Layanan YouTube dan berisiko ditolak/ditarik dari Google Play
(kebijakan Play soal mengganggu layanan pihak ketiga). Switch di Pengaturan TIDAK cukup untuk Play: kodenya
tetap ada di dalam APK/AAB. Karena itu kodenya dipisah per varian build (flavor dimensi `dist`):

| Varian | Isi | Untuk |
|---|---|---|
| `play` (playFree / playPremium) | YouTube dimuat apa adanya (iklan tampil), TIDAK ada kode pemblokir (stub kosong di `app/src/play`), tangkap audio YouTube ke rekaman MATI (`YT_RECORD_CAPTURE=false`) | Google Play (`bundlePlayFreeRelease`, `bundlePlayPremiumRelease`) |
| `mod` (modFree / modPremium) | Pemblokir iklan + switch di Pengaturan + rekam audio YouTube + sinkron otomatis. applicationId `.mod` (bisa dipasang berdampingan) | APK langsung / grup / GitHub - JANGAN diunggah ke Play |

Pemblokir iklan (`app/src/mod/.../YouTubeAdBlock.kt`) - tiga lapis, semuanya lokal:
1. **Jaringan** - `shouldInterceptRequest` memutus host/jalur iklan & pelacak (`HOST_PARTS`/`PATH_PARTS`).
2. **Data** - script disuntik di awal dokumen membuang `adPlacements`, `playerAds`, `adSlots`.
3. **Kosmetik + darurat** - CSS menyembunyikan elemen iklan; pelompat iklan hanya bertindak saat pemutar menandai `ad-showing`.
Switch "Blokir iklan YouTube" (Pengaturan, hanya mod): `YouTubeState.setAdBlock` memasang/melepas script lalu memuat ulang.
Jembatan slider/volume/rekam ada di `YouTubeBridgeJs.kt` (semua varian) dan tidak bergantung pada pemblokir.

## Slider menit / volume / play (jembatan `window.DtxYt`)
Script mengirim `state(posisi, durasi, playing, judul, iklan, valid)` tiap 400 ms ke `YouTubeState`; perintah
kembali lewat `window.__dtx.{play,pause,seek,vol,cap}`. Slider MUSIK adalah satu slider untuk dua sumber
(`onMusicVolumeChange` mengatur engine lokal dan YouTube sekaligus).

## Rekaman sinkron dengan kendang (ditulis ulang v1.16, hanya varian mod)
Audio WebView tidak lewat engine, jadi REC / REC LAYAR menangkapnya lewat jalur PCM:
1. Saat rekam mulai, `<video>` dialihkan lewat Web Audio dan ditangkap **AudioWorklet** (thread audio, tidak ikut macet
   saat JS YouTube sibuk; cadangan ScriptProcessor 4096). Tiap potongan membawa **nomor frame jam audio**.
2. PCM dikirim ke native (`nativeFeedExternalRecording(pcm, frames, startFrame)`) dan ditaruh di ring **berindeks frame**
   (bukan FIFO berdasar waktu tiba). Telat/bergerombol tidak menggeser musik; bolong diisi hening; underrun tidak
   memicu isi-ulang buffer (penyebab suara tersendat/glitch di v1.15).
3. `PadEngine::render` mencampurnya HANYA ke buffer rekaman. Pembaca tertinggal `PREFILL_MS` (300 ms) dari data terbaru.
4. Jalur kendang di rekaman ditunda `drumDelay` (terpisah dari prefill sejak v1.16, dikalibrasi). `stopRecording()` mengeluarkan
   isi garis tunda sehingga pukulan terakhir tidak terpotong.

### Kalibrasi sinkron otomatis
Tiap mulai memutar YouTube (pertama kali / setelah jeda > 30 dtk) dan lewat tombol KALIBRASI di Pengaturan:
rekam internal (dibuang) -> pad membunyikan **klik penanda** ("klik sukses") pada waktu T dikurangi latensi output,
sisi YouTube mencatat nomor frame yang berbunyi di speaker pada T (`getOutputTimestamp`) -> engine mencatat posisi
keduanya di file -> selisihnya dipakai menggeser `drumDelay` (disimpan di SharedPreferences `dtx_youtube`).
Batasan: jalur digital terukur presisi; selisih latensi speaker WebView vs Oboe hanya perkiraan -> sisa error
dikoreksi dengan slider "Koreksi manual" (+-100 ms). Belum diuji di perangkat: lihat CHECKPOINT.md.

## File
`YouTubeState.kt` (WebView, jembatan, kendali, kalibrasi), `YouTubeMode.kt` (layar + toolbar), `YouTubeBridgeJs.kt`
(navigasi + jembatan JS), `app/src/mod|play/.../YouTubeAdBlock.kt` (aturan + script / stub), `SkinToggleTab.kt` (tombol tab), `MusicLibraryDialog.kt` (pintu kedua), `MainActivity.kt`
(routing panel musik, rekam, lifecycle), `cpp/PadEngine.*` + `native-lib.cpp` (masukan eksternal rekaman).
