package com.kendang.realpads

import android.content.Context
import android.net.Uri

/**
 * Varian PLAY (Google Play): stub kosong. Tidak ada pemblokir iklan, tidak ada daftar host iklan, tidak ada
 * script penyuntik. YouTube dimuat apa adanya (iklan tampil normal). API-nya sama dengan versi di app/src/mod
 * supaya kode lain (YouTubeState, Pengaturan) tidak perlu cabang per-varian.
 */
object YouTubeAdBlock {
    const val AVAILABLE = false
    val enabled: Boolean get() = false
    fun loadPref(ctx: Context) {}
    fun savePref(ctx: Context, on: Boolean) {}
    @Suppress("UNUSED_PARAMETER")
    fun shouldBlock(uri: Uri): Boolean = false
    const val SCRIPT: String = ""
}
