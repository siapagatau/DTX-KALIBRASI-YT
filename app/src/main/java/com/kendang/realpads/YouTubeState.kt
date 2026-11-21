package com.kendang.realpads

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.media.AudioManager
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.WebViewCompat
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * YOUTUBE IN-APP (v1.15) - sumber musik tambahan selain file lokal, tanpa perlu mengunduh lagu.
 *
 * Satu WebView PERMANEN (dibuat sekali, tidak pernah dilepas dari layar selama app hidup) menuju
 * youtube.com dengan pemblokir iklan (YouTubeAdBlock). Menyalakan/mematikan MODE YouTube hanya mengubah
 * ukuran tampilannya (penuh <-> 1dp), BUKAN membuang/membuat ulang WebView - itulah yang mencegah musik
 * "main sebentar lalu berhenti" (WebView yang di-detach/di-pause/disembunyikan dijeda oleh Chromium).
 *
 * Panel musik kanan (slider menit, volume, play/stop) otomatis terhubung ke sini selama YouTube yang
 * terakhir dimainkan (sourceActive). Audio YouTube ikut terekam bareng kendang lewat jalur PCM ke
 * PadEngine (lihat setCapture + PadEngine::setExternalRecording).
 */
object YouTubeState {
    private const val TAG = "YouTubeState"

    // v1.16: dua nilai yang dulu dipaksa sama (REC_DELAY_MS = 80) sekarang terpisah:
    //  - PREFILL_MS      : jitter buffer musik YouTube di sisi native. Besar (300 ms) supaya kiriman yang telat dari
    //                      WebView (JS YouTube sering macet 100-250 ms) tidak jadi bolong/glitch di rekaman.
    //  - drum delay (ms) : penundaan jalur kendang di file rekaman. Ini yang DIKALIBRASI otomatis (calibrateSync)
    //                      sehingga kendang & musik jatuh di tempat yang sama persis seperti yang terdengar.
    private const val PREFILL_MS = 300
    private const val DEFAULT_DRUM_DELAY_MS = 280   // tebakan awal (prefill + jembatan JS - selisih latensi output)
    private const val MAX_DRUM_DELAY_MS = 400
    private const val PREFS = "dtx_youtube"
    private const val K_SYNC_DELAY = "sync_delay_ms"
    private const val K_SYNC_TRIM = "sync_trim_ms"
    private const val K_AUTO_SYNC = "auto_sync"
    private const val K_SYNC_DONE = "sync_done"

    private val main = Handler(Looper.getMainLooper())

    // --- State UI (dibaca Compose) ---
    var modeOn by mutableStateOf(false)
        private set
    // WebView baru dibuat saat mode YouTube pertama kali dibuka (tidak membebani startup app).
    var everOpened by mutableStateOf(false)
        private set
    var hasMedia by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var positionSec by mutableStateOf(0.0)
        private set
    var durationSec by mutableStateOf(0.0)
        private set
    var title by mutableStateOf("")
        private set
    var adShowing by mutableStateOf(false)
        private set
    var blockedCount by mutableStateOf(0)
        private set
    var loading by mutableStateOf(false)
        private set
    var progress by mutableStateOf(100)
        private set
    var canGoBack by mutableStateOf(false)
        private set
    var canGoForward by mutableStateOf(false)
        private set
    // Naik tiap WebView memuat dokumen baru -> MainActivity menyetel ulang volume ke halaman baru.
    var pageGeneration by mutableStateOf(0)
        private set

    // --- Sinkron rekaman (v1.16) ---
    /** Penundaan kendang hasil kalibrasi (ms). */
    var syncDelayMs by mutableStateOf(DEFAULT_DRUM_DELAY_MS)
        private set
    /** Koreksi manual pengguna (ms, -100..+100) di atas hasil kalibrasi. */
    var syncTrimMs by mutableStateOf(0)
        private set
    var autoSync by mutableStateOf(true)
        private set
    var calibrated by mutableStateOf(false)
        private set
    var calibrating by mutableStateOf(false)
        private set
    var syncStatus by mutableStateOf("")
        private set
    /** Switch iklan (hanya bermakna di varian mod; di varian play selalu false). */
    var adBlockOn by mutableStateOf(false)
        private set

    // True kalau YouTube adalah sumber musik yang sedang "dipegang" panel musik.
    private var claimed by mutableStateOf(false)
    val sourceActive: Boolean get() = claimed && hasMedia

    private var webView: WebView? = null
    private var appContext: Context? = null
    @Volatile private var captureWanted = false
    private var lastVolume = 1f
    private var docStartSupported = false
    private var bridgeHandler: ScriptHandler? = null
    private var adHandler: ScriptHandler? = null

    // Kalibrasi / penanda
    @Volatile private var pcmChunks = 0
    @Volatile private var markerArmed = false
    private var calibToken = Any()
    private var lastPausedAt = 0L
    private var autoCalibScheduled = false

    /** WebView yang sudah ada (null kalau belum dibuat). Dipakai YouTubeLayer untuk membuatnya di frame terpisah. */
    fun webViewOrNull(): WebView? = webView

    /** Tombol "tangkap audio YouTube ke rekaman" hanya ada di build yang mengizinkannya (varian mod). */
    val captureAvailable: Boolean get() = BuildConfig.YT_RECORD_CAPTURE
    val adBlockAvailable: Boolean get() = YouTubeAdBlock.AVAILABLE

    /** Dipanggil sekali (MainActivity) untuk memuat preferensi. Aman dipanggil berulang. */
    fun loadPrefs(ctx: Context) {
        appContext = ctx.applicationContext
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        syncDelayMs = sp.getInt(K_SYNC_DELAY, DEFAULT_DRUM_DELAY_MS).coerceIn(0, MAX_DRUM_DELAY_MS)
        syncTrimMs = sp.getInt(K_SYNC_TRIM, 0).coerceIn(-100, 100)
        autoSync = sp.getBoolean(K_AUTO_SYNC, true)
        calibrated = sp.getBoolean(K_SYNC_DONE, false)
        YouTubeAdBlock.loadPref(ctx)
        adBlockOn = YouTubeAdBlock.AVAILABLE && YouTubeAdBlock.enabled
    }

    private fun sp(): android.content.SharedPreferences? =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setAutoSync(on: Boolean) { autoSync = on; sp()?.edit()?.putBoolean(K_AUTO_SYNC, on)?.apply() }

    fun setSyncTrim(ms: Int) {
        syncTrimMs = ms.coerceIn(-100, 100)
        sp()?.edit()?.putInt(K_SYNC_TRIM, syncTrimMs)?.apply()
    }

    /** Penundaan kendang efektif di rekaman = hasil kalibrasi + koreksi manual. */
    private fun effectiveDrumDelayMs(): Int = (syncDelayMs + syncTrimMs).coerceIn(0, MAX_DRUM_DELAY_MS)

    /** Switch pemblokir iklan di Pengaturan (varian mod). Memuat ulang halaman supaya langsung berlaku. */
    fun setAdBlock(ctx: Context, on: Boolean) {
        if (!YouTubeAdBlock.AVAILABLE) return
        YouTubeAdBlock.savePref(ctx, on)
        adBlockOn = on
        val wv = webView ?: return
        installAdBlockScript(wv)
        wv.reload()
    }

    // True kalau YouTube adalah sumber musik yang sedang "dipegang" panel musik.
    // ---------------------------------------------------------------------------------------------
    // Mode (tombol di tab kanan, sama seperti mode kit / edit / mixer)
    // ---------------------------------------------------------------------------------------------
    fun enter() { everOpened = true; modeOn = true }
    fun exit() { modeOn = false }
    fun toggle() { if (modeOn) exit() else enter() }

    // ---------------------------------------------------------------------------------------------
    // Kendali musik (dipanggil MainActivity lewat panel musik / skin)
    // ---------------------------------------------------------------------------------------------
    fun togglePlay() { if (playing) pause() else play() }

    fun play() {
        claimed = true
        js("window.__dtx&&window.__dtx.play()")
    }

    fun pause() { js("window.__dtx&&window.__dtx.pause()") }

    /** Lepas kepemilikan sumber musik (dipanggil saat lagu LOKAL mulai dipakai) dan jeda YouTube. */
    fun releaseSource() {
        if (playing) pause()
        claimed = false
    }

    fun seek(seconds: Double) {
        positionSec = seconds // optimistis, supaya slider tidak "loncat balik" menunggu laporan berikutnya
        js("window.__dtx&&window.__dtx.seek(${seconds})")
    }

    fun setVolume(v: Float) {
        lastVolume = v
        js("window.__dtx&&window.__dtx.vol(${v})")
    }

    /** Dipanggil saat rekaman (REC / REC LAYAR) mulai. Harus dipanggil SEBELUM native record mulai. */
    fun prepareRecording(): Boolean {
        if (calibrating) abortCalibration()   // kalibrasi memakai jalur rekam yang sama -> kalah dari rekaman asli
        // Hanya relevan kalau YouTube sedang jadi sumber musik (dan build ini mengizinkan penangkapan);
        // selain itu rekaman persis seperti sebelumnya.
        val use = captureAvailable && webView != null && sourceActive
        runCatching {
            AudioEngine.nativeSetExternalRecording(use, if (use) PREFILL_MS else 0, if (use) effectiveDrumDelayMs() else 0)
        }
        return use
    }

    fun setCapture(on: Boolean) {
        if (!captureAvailable) return
        captureWanted = on
        js("window.__dtx&&window.__dtx.cap(${on})")
    }

    fun goBack() { webView?.let { if (it.canGoBack()) it.goBack() } }
    fun goForward() { webView?.let { if (it.canGoForward()) it.goForward() } }
    fun reload() { webView?.reload() }
    fun goHome() { webView?.loadUrl(YouTubeNav.HOME_URL) }

    /** Jeda saat app benar-benar ke background (onStop). Tidak dilanjutkan otomatis. */
    fun onAppStopped() { if (playing) pause() }

    /** Dipanggil MainActivity.onDestroy: bongkar WebView supaya tidak menahan Activity lama. */
    fun release() {
        val wv = webView ?: return
        webView = null
        runCatching { (wv.parent as? ViewGroup)?.removeView(wv) }
        runCatching { wv.stopLoading() }
        runCatching { wv.destroy() }
        hasMedia = false; playing = false; claimed = false; modeOn = false
        calibToken = Any(); calibrating = false
        positionSec = 0.0; durationSec = 0.0; title = ""
    }

    // ---------------------------------------------------------------------------------------------
    // KALIBRASI SINKRON OTOMATIS (v1.16)
    //
    // Tujuan: kendang & musik YouTube di FILE REKAMAN jatuh persis seperti yang terdengar, tanpa pengguna
    // mengutak-atik angka. Cara kerja - memakai JALUR REKAM YANG SAMA dengan REC / REC LAYAR:
    //  1. mulai rekam internal (dibuang, tidak disimpan) + nyalakan tangkap audio YouTube;
    //  2. pilih satu waktu T (epoch) sekitar 0,5 dtk lagi. Dari pad dibunyikan KLIK PENANDA ("klik sukses") pada
    //     T - latensi output Oboe; dari sisi YouTube dicatat nomor frame yang berbunyi di speaker tepat pada T
    //     (getOutputTimestamp). Kalau latensi kedua jalur dikoreksi dengan benar, keduanya TERDENGAR bersamaan;
    //  3. engine mencatat di posisi file mana kedua penanda itu mendarat. Selisihnya = berapa ms penundaan
    //     kendang harus digeser supaya kembali sejajar. Hasil disimpan dan dipakai semua rekaman berikutnya.
    // Batasan jujur: kalibrasi ini mengukur jalur digital (jembatan JS, buffer, antrean) dengan presisi, tapi
    // selisih latensi SPEAKER antara WebView dan Oboe hanya diperkirakan dari nilai yang dilaporkan sistem.
    // Sisa error kecil bisa dikoreksi dengan slider "Koreksi manual" di Pengaturan.
    // ---------------------------------------------------------------------------------------------
    private fun onPlayStarted() {
        if (!captureAvailable || !autoSync || calibrating || autoCalibScheduled || appContext == null) return
        val now = System.currentTimeMillis()
        // Hanya saat benar-benar "mulai memutar" (pertama kali, atau setelah jeda > 30 dtk), bukan tiap lanjut dari pause.
        if (lastPausedAt != 0L && now - lastPausedAt < 30_000L) return
        autoCalibScheduled = true
        main.postDelayed({ autoCalibScheduled = false; calibrateSync(auto = true) }, 2500)
    }

    private fun estimateOutputLatencyMs(): Int {
        val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val fpb = am?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256
        var ms = fpb * 3000 / 48000                       // ~3 buffer burst
        if (AudioBackendManager.active == 4) ms += 30     // backend AudioTrack lebih lambat
        return ms.coerceIn(12, 90)
    }

    private fun say(msg: String) {
        syncStatus = msg
        val c = appContext ?: return
        runCatching { Toast.makeText(c, msg, Toast.LENGTH_SHORT).show() }
    }

    fun calibrateSync(auto: Boolean = false) {
        if (!captureAvailable || calibrating) return
        if (webView == null || !sourceActive || !playing) {
            if (!auto) say("Putar lagu dari YouTube dulu, lalu kalibrasi")
            return
        }
        if (runCatching { AudioEngine.nativeIsRecording() }.getOrDefault(false)) {
            if (!auto) say("Sedang merekam - kalibrasi nanti")
            return
        }
        val token = Any()
        calibToken = token
        calibrating = true
        markerArmed = false
        pcmChunks = 0
        syncStatus = "Kalibrasi sinkron..."
        val used = syncDelayMs
        runCatching {
            AudioEngine.nativeSetExternalRecording(true, PREFILL_MS, used)
            AudioEngine.nativeStartRecording()
        }
        setCapture(true)
        main.postDelayed({ if (calibToken === token) calibFire(token, used, auto) }, 1000)
    }

    private fun calibFire(token: Any, used: Int, auto: Boolean) {
        if (pcmChunks < 3) {
            finishCalibration(token, "Audio YouTube belum mengalir - coba lagi saat lagu jalan", auto)
            return
        }
        val fireAt = System.currentTimeMillis() + 500L
        js("window.__dtx&&window.__dtx.marker(${fireAt})")
        val wait = (fireAt - estimateOutputLatencyMs() - System.currentTimeMillis()).coerceAtLeast(0L)
        main.postDelayed({
            if (calibToken === token) runCatching { AudioEngine.nativePlaySyncMarker() }   // <- "klik sukses" dari pad
        }, wait)
        main.postDelayed({ if (calibToken === token) calibRead(token, used, auto) }, 500L + PREFILL_MS + 700L)
    }

    private fun calibRead(token: Any, used: Int, auto: Boolean) {
        val res = runCatching { AudioEngine.nativeGetSyncResult() }.getOrNull()
        if (!markerArmed || res == null || res.size < 2 || res[0] < 0 || res[1] < 0) {
            finishCalibration(token, "Kalibrasi gagal: penanda tidak tercatat", auto)
            return
        }
        val deltaMs = (res[0] - res[1]) / 48.0
        if (kotlin.math.abs(deltaMs) > 350.0) {
            finishCalibration(token, "Kalibrasi diabaikan (hasil ${deltaMs.toInt()} ms tidak wajar)", auto)
            return
        }
        val newDelay = (used + deltaMs).toInt().coerceIn(0, MAX_DRUM_DELAY_MS)
        syncDelayMs = newDelay
        calibrated = true
        sp()?.edit()?.putInt(K_SYNC_DELAY, newDelay)?.putBoolean(K_SYNC_DONE, true)?.apply()
        val sign = if (deltaMs >= 0) "+" else ""
        finishCalibration(token, "Sinkron otomatis OK (kendang $newDelay ms, geser $sign${deltaMs.toInt()} ms)", auto)
    }

    private fun finishCalibration(token: Any, msg: String, auto: Boolean) {
        if (calibToken !== token) return
        calibToken = Any()
        calibrating = false
        // Buang hasil rekam internal. Jangan menyentuh native kalau pengguna keburu merekam sendiri.
        runCatching { AudioEngine.nativeStopRecording() }
        runCatching { AudioEngine.nativeSetExternalRecording(false, 0, 0) }
        setCapture(false)
        if (!auto || msg.startsWith("Sinkron otomatis OK")) say(msg) else syncStatus = msg
    }

    private fun abortCalibration() {
        calibToken = Any()
        calibrating = false
        syncStatus = ""
        runCatching { AudioEngine.nativeStopRecording() }
    }

    private fun js(code: String) {
        val wv = webView ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { wv.evaluateJavascript(code, null) }
        } else {
            main.post { runCatching { wv.evaluateJavascript(code, null) } }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // WebView
    // ---------------------------------------------------------------------------------------------
    private fun activityOf(ctx: Context): Context {
        var c = ctx
        while (c is ContextWrapper) {
            if (c is android.app.Activity) return c
            c = c.baseContext
        }
        return ctx
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun ensureWebView(context: Context): WebView {
        webView?.let { return it }
        if (appContext == null) loadPrefs(context)
        val wv = WebView(activityOf(context))
        wv.setBackgroundColor(Color.BLACK)
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        val st = wv.settings
        st.javaScriptEnabled = true
        st.domStorageEnabled = true
        st.mediaPlaybackRequiresUserGesture = false   // tanpa ini YouTube butuh ketukan ekstra & sering "main lalu berhenti"
        st.javaScriptCanOpenWindowsAutomatically = false
        st.setSupportMultipleWindows(false)
        st.cacheMode = WebSettings.LOAD_DEFAULT
        st.loadWithOverviewMode = true
        st.useWideViewPort = true
        st.builtInZoomControls = false
        st.displayZoomControls = false
        st.allowFileAccess = false
        st.allowContentAccess = false

        runCatching {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(wv, true)
        }

        wv.addJavascriptInterface(Bridge(), "DtxYt")

        // Suntik script di AWAL dokumen (sebelum script milik YouTube jalan). Jembatan (slider/volume/rekam) selalu;
        // pemblokir iklan hanya di varian mod dan hanya saat switch-nya nyala (installAdBlockScript).
        // Cadangan untuk WebView lama: evaluateJavascript di onPageStarted/onPageFinished.
        docStartSupported = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        }.getOrDefault(false)
        if (docStartSupported) {
            runCatching {
                bridgeHandler = WebViewCompat.addDocumentStartJavaScript(wv, YouTubeBridgeJs.SCRIPT, setOf("https://*.youtube.com"))
            }.onFailure { docStartSupported = false; Log.e(TAG, "addDocumentStartJavaScript gagal", it) }
        }
        installAdBlockScript(wv)

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (YouTubeAdBlock.AVAILABLE && YouTubeAdBlock.shouldBlock(request.url)) {
                    main.post { blockedCount += 1 }
                    return WebResourceResponse(
                        "text/plain", "utf-8", 204, "No Content",
                        mapOf("Access-Control-Allow-Origin" to "*"),
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val ok = YouTubeNav.isAllowedNavigation(request.url)
                if (!ok && request.isForMainFrame) {
                    Toast.makeText(view.context, "Link keluar YouTube diblokir", Toast.LENGTH_SHORT).show()
                }
                return !ok
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                loading = true
                if (!docStartSupported) injectFallbackScripts(view)
                refreshNav(view)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                loading = false
                if (!docStartSupported) injectFallbackScripts(view)
                refreshNav(view)
                pageGeneration += 1
                if (captureWanted) js("window.__dtx&&window.__dtx.cap(true)")
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                refreshNav(view)
            }
        }
        // WebChromeClient default (tanpa onPermissionRequest/onShowCustomView): izin kamera/mik/lokasi
        // ditolak, dan tombol layar-penuh YouTube tidak melakukan apa-apa. Cukup untuk fungsi musik.
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
        }

        webView = wv
        wv.loadUrl(YouTubeNav.HOME_URL)
        return wv
    }

    private fun injectFallbackScripts(view: WebView) {
        view.evaluateJavascript(YouTubeBridgeJs.SCRIPT, null)
        if (YouTubeAdBlock.AVAILABLE && YouTubeAdBlock.enabled) view.evaluateJavascript(YouTubeAdBlock.SCRIPT, null)
    }

    /** Pasang/lepas script pemblokir iklan sesuai switch. Di varian play: tidak melakukan apa-apa. */
    private fun installAdBlockScript(wv: WebView) {
        if (!YouTubeAdBlock.AVAILABLE) return
        runCatching { adHandler?.remove() }
        adHandler = null
        if (docStartSupported && YouTubeAdBlock.enabled) {
            runCatching {
                adHandler = WebViewCompat.addDocumentStartJavaScript(wv, YouTubeAdBlock.SCRIPT, setOf("https://*.youtube.com"))
            }.onFailure { Log.e(TAG, "ad script gagal dipasang", it) }
        }
    }

    private fun refreshNav(view: WebView) {
        canGoBack = view.canGoBack()
        canGoForward = view.canGoForward()
    }

    // ---------------------------------------------------------------------------------------------
    // Jembatan JS -> native (thread "JavaBridge", bukan UI thread)
    // ---------------------------------------------------------------------------------------------
    private class Bridge {
        private var wasPlaying = false

        @JavascriptInterface
        fun state(pos: Double, dur: Double, isPlaying: Boolean, ttl: String?, ad: Boolean, ok: Boolean) {
            YouTubeState.main.post {
                YouTubeState.hasMedia = ok
                YouTubeState.positionSec = if (pos.isNaN()) 0.0 else pos
                YouTubeState.durationSec = if (dur.isNaN()) 0.0 else dur
                YouTubeState.playing = isPlaying && ok
                YouTubeState.title = ttl ?: ""
                YouTubeState.adShowing = ad
                // YouTube mulai berbunyi (dari mana pun: ketukan di halaman atau tombol panel) -> jadi sumber
                // musik, dan lagu lokal dijeda supaya tidak ada dua musik sekaligus.
                if (isPlaying && ok && !wasPlaying) {
                    YouTubeState.claimed = true
                    runCatching { AudioEngine.nativeStopMusic() }
                    YouTubeState.onPlayStarted()
                }
                if (!(isPlaying && ok) && wasPlaying) YouTubeState.lastPausedAt = System.currentTimeMillis()
                wasPlaying = isPlaying && ok
            }
        }

        @JavascriptInterface
        fun pcm(b64: String?, sampleRate: Int, startFrame: Double) {
            if (b64.isNullOrEmpty() || !YouTubeState.captureWanted) return
            try {
                val bytes = Base64.decode(b64, Base64.NO_WRAP)
                val total = bytes.size / 2
                if (total < 2 || startFrame.isNaN() || startFrame < 0) return
                val sh = ShortArray(total)
                ByteBuffer.wrap(bytes, 0, total * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(sh)
                YouTubeState.pcmChunks += 1
                if (sampleRate == 48000 || sampleRate <= 0) {
                    AudioEngine.nativeFeedExternalRecording(sh, total / 2, startFrame.toLong())
                } else {
                    // Konteks audio tidak bisa dipaksa 48 kHz -> resample linear ke 48 kHz (nomor frame ikut diskalakan).
                    val inFrames = total / 2
                    val outFrames = (inFrames.toLong() * 48000L / sampleRate).toInt()
                    if (outFrames < 1) return
                    val out = ShortArray(outFrames * 2)
                    val ratio = sampleRate.toDouble() / 48000.0
                    for (i in 0 until outFrames) {
                        val p = i * ratio
                        val i0 = p.toInt().coerceAtMost(inFrames - 1)
                        val i1 = (i0 + 1).coerceAtMost(inFrames - 1)
                        val f = (p - i0).toFloat()
                        for (c in 0..1) {
                            val a = sh[i0 * 2 + c].toInt()
                            val b = sh[i1 * 2 + c].toInt()
                            out[i * 2 + c] = (a + (b - a) * f).toInt().toShort()
                        }
                    }
                    AudioEngine.nativeFeedExternalRecording(out, outFrames, (startFrame * 48000.0 / sampleRate).toLong())
                }
            } catch (t: Throwable) {
                Log.e(TAG, "pcm gagal", t)
            }
        }

        // Nomor frame (jam audio JS) tempat penanda kalibrasi berbunyi di speaker (lihat __dtx.marker).
        @JavascriptInterface
        fun markerFrame(frame: Double, sampleRate: Int) {
            if (frame.isNaN() || frame < 0) return
            val f = if (sampleRate == 48000 || sampleRate <= 0) frame else frame * 48000.0 / sampleRate
            runCatching { AudioEngine.nativeArmExternalMarker(f.toLong()) }
            YouTubeState.markerArmed = true
        }
    }
}
