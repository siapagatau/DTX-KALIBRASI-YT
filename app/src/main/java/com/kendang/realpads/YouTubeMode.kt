package com.kendang.realpads

import android.app.Activity
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clipToBounds
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Lapisan YouTube (v1.15): menggantikan area pad selama MODE YouTube nyala (tombol di tab kanan, sejajar
 * mode edit / mixer / kit). Panel preset, panel musik, dan strip tombol tetap berfungsi seperti biasa.
 *
 * PENTING (inti perbaikan "klik play lalu berhenti"): composable ini dipasang di SATU posisi tetap dan
 * WebView-nya TIDAK PERNAH dilepas dari layar. Saat mode ditutup, lapisan cuma digeser jauh ke luar layar
 * (offset) - WebView tetap menempel & terlihat bagi Chromium, jadi musiknya lanjut jalan di belakang pad.
 * Kalau dilepas (if/else biasa) Chromium menjeda media-nya.
 */
@Composable
fun YouTubeLayer(
    visible: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // v1.16 PERBAIKAN "tombol kiri hilang sesaat saat YouTube dimuat": dulu WebView dibuat LANGSUNG di dalam
    // komposisi (WebView() + inisialisasi Chromium = ratusan ms di thread UI, tepat di frame yang sama dengan
    // perubahan layout) sehingga frame itu tampil tanpa panel kiri / dengan bar sistem berganti-ganti.
    // Sekarang: frame pertama digambar penuh dulu (panel kiri utuh + layar "Memuat YouTube"), baru dua frame
    // kemudian WebView dibuat; lapisannya di-clip supaya tidak menggambar di luar areanya, dan mode
    // layar-penuh (bar sistem tersembunyi) ditegaskan ulang setelah WebView siap.
    var wv by remember { mutableStateOf<WebView?>(YouTubeState.webViewOrNull()) }
    LaunchedEffect(Unit) {
        if (wv == null) {
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.withFrameNanos { }
            wv = YouTubeState.ensureWebView(context)
            (context as? Activity)?.window?.let { w ->
                WindowCompat.setDecorFitsSystemWindows(w, false)
                WindowInsetsControllerCompat(w, w.decorView).hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Tombol kembali: mundur di riwayat YouTube dulu, baru tutup mode.
    BackHandler(enabled = visible) {
        if (YouTubeState.canGoBack) YouTubeState.goBack() else onClose()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            // Tidak tampil = digeser jauh keluar layar (bukan dihapus dari komposisi, bukan ukuran 0).
            .then(if (visible) Modifier else Modifier.offset(x = (-20000).dp))
            .background(Color.Black)
    ) {
        if (visible) {
            YouTubeToolbar(onClose = onClose)
        }
        val view = wv
        if (view != null) {
            AndroidView(
                factory = {
                    // Jaga-jaga: WebView yang sama tidak boleh punya dua induk.
                    (view.parent as? ViewGroup)?.removeView(view)
                    view
                },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Text("Memuat YouTube…", color = TextDim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun YouTubeToolbar(onClose: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(CtrlPanelColor)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(34.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BarIcon(Icons.Filled.ArrowBack, "Kembali", enabled = YouTubeState.canGoBack) { YouTubeState.goBack() }
            BarIcon(Icons.Filled.ArrowForward, "Maju", enabled = YouTubeState.canGoForward) { YouTubeState.goForward() }
            BarIcon(Icons.Filled.Home, "Beranda YouTube", enabled = true) { YouTubeState.goHome() }
            BarIcon(Icons.Filled.Refresh, "Muat ulang", enabled = true) { YouTubeState.reload() }
            Text(
                text = when {
                    YouTubeState.adShowing -> if (YouTubeState.adBlockOn) "Melewati iklan..." else "Iklan YouTube"
                    YouTubeState.title.isNotBlank() -> YouTubeState.title
                    else -> "YouTube"
                },
                color = PadTextColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
            )
            if (YouTubeState.adBlockOn) {
                Text(
                    text = "${YouTubeState.blockedCount} iklan/pelacak diblokir",
                    color = TextDim,
                    fontSize = 9.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(ThemeState.accentColor)
                    .clickable { onClose() }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Tutup mode YouTube",
                        tint = ThemeState.accentTextColor,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        " TUTUP",
                        color = ThemeState.accentTextColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }
        // Garis kemajuan muat halaman (tipis, hanya saat memuat).
        Box(modifier = Modifier.fillMaxWidth().height(2.dp)) {
            if (YouTubeState.loading && YouTubeState.progress < 100) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(YouTubeState.progress / 100f)
                        .background(ThemeState.accentColor)
                )
            }
        }
    }
}

@Composable
private fun BarIcon(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(34.dp)
            .fillMaxHeight()
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) PadTextColor else TextDim.copy(alpha = 0.45f),
            modifier = Modifier.size(16.dp)
        )
    }
}
