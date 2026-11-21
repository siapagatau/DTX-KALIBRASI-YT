package com.kendang.realpads

import android.content.Context
import android.net.Uri

// Pemblokir iklan YouTube ala Brave, versi ringkas untuk WebView in-app (lihat YouTubeState).
// Tiga lapis, semuanya lokal (tanpa library/server tambahan):
//  1. JARINGAN  : permintaan ke domain/jalur iklan & pelacak diputus di shouldInterceptRequest.
//  2. DATA      : script yang disuntik di AWAL dokumen membuang kunci iklan (adPlacements, playerAds,
//                 adSlots) dari respons pemutar YouTube sebelum pemutar membacanya -> iklan tidak pernah dijadwalkan.
//  3. KOSMETIK  : CSS menyembunyikan sisa elemen iklan + pelompat iklan darurat (hanya aktif kalau pemutar
//                 BENAR-BENAR menandai "ad-showing", jadi video biasa tidak pernah dilompati).
// YouTube sering mengubah struktur halamannya: kalau suatu saat iklan lolos, yang perlu diperbarui hanya
// daftar di file ini (HOST_PARTS / PATH_PARTS / CSS / selector di SCRIPT).
//
// v1.16: file ini HANYA ada di varian "mod" (app/src/mod). Varian "play" (Google Play) memakai stub kosong di
// app/src/play dengan API yang sama, jadi build Play tidak memuat satu baris pun kode pemblokir iklan.
// Pemblokiran bisa dimatikan/dinyalakan lewat switch di Pengaturan (YouTubeAdBlock.enabled).
object YouTubeAdBlock {

    const val AVAILABLE = true

    private const val PREFS = "dtx_youtube"
    private const val KEY_ENABLED = "adblock_enabled"

    /** Switch runtime (Pengaturan). Default nyala. Dibaca di jalur jaringan (thread WebView) -> @Volatile. */
    @Volatile var enabled: Boolean = true

    fun loadPref(ctx: Context) {
        enabled = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
    }

    fun savePref(ctx: Context, on: Boolean) {
        enabled = on
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    // Potongan host yang diblokir total.
    private val HOST_PARTS = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.",
        "imasdk.googleapis.com",
        "2mdn.net",
        "moatads.com",
        "google-analytics.com",
        "googletagmanager.com",
        "googletagservices.com",
        "adsrvr.org",
        "scorecardresearch.com"
    )

    // Potongan jalur yang diblokir di host mana pun yang masih diizinkan (mis. youtube.com).
    private val PATH_PARTS = listOf(
        "/pagead/",
        "/api/stats/ads",
        "/ptracking",
        "/get_midroll_",
        "/generate_204_ad",
        "/youtubei/v1/ad_break",
        "ad_break",
        "/api/stats/atr_ad"
    )

    fun shouldBlock(uri: Uri): Boolean {
        if (!enabled) return false
        val host = (uri.host ?: return false).lowercase()
        for (h in HOST_PARTS) if (host.contains(h)) return true
        val path = (uri.encodedPath ?: "").lowercase()
        if (host.endsWith("youtube.com") || host.endsWith("googlevideo.com")) {
            for (p in PATH_PARTS) if (path.contains(p)) return true
            // Permintaan video iklan di googlevideo membawa parameter ini.
            val q = (uri.encodedQuery ?: "")
            if (host.endsWith("googlevideo.com") && q.contains("adformat=")) return true
        }
        return false
    }

    // Script pemblokir (bagian 1-4: visibilitas palsu, pembuang data iklan, CSS kosmetik, pelompat iklan).
    // Disuntik di awal dokumen hanya saat switch nyala (lihat YouTubeState.applyAdBlockSetting).
    // Jembatan slider/volume/rekam ada di YouTubeBridgeJs (main), TIDAK bergantung pada file ini.
    val SCRIPT: String = """

(function(){
  if (window.__dtxAdInit) return; window.__dtxAdInit = true;

  // 1) Halaman dianggap SELALU terlihat -> YouTube tidak menjeda saat WebView tersembunyi (mode YouTube
  //    ditutup, musik tetap jalan di belakang pad).
  try {
    Object.defineProperty(document,'hidden',{get:function(){return false},configurable:true});
    Object.defineProperty(document,'webkitHidden',{get:function(){return false},configurable:true});
    Object.defineProperty(document,'visibilityState',{get:function(){return 'visible'},configurable:true});
    Object.defineProperty(document,'webkitVisibilityState',{get:function(){return 'visible'},configurable:true});
    var stop=function(e){e.stopImmediatePropagation();};
    document.addEventListener('visibilitychange',stop,true);
    document.addEventListener('webkitvisibilitychange',stop,true);
    window.addEventListener('pagehide',stop,true);
  } catch(e){}

  // 2) Buang data iklan dari respons YouTube.
  var KEYS=['adPlacements','playerAds','adSlots','adBreakHeartbeatParams'];
  function strip(o){
    try{
      if(o && typeof o==='object'){
        for(var i=0;i<KEYS.length;i++){ if(KEYS[i] in o){ try{ delete o[KEYS[i]]; }catch(e){ o[KEYS[i]]=undefined; } } }
        if(o.playerResponse && typeof o.playerResponse==='object') strip(o.playerResponse);
        if(o.response && typeof o.response==='object' && o.response.playerResponse) strip(o.response.playerResponse);
      }
    }catch(e){}
    return o;
  }
  try{
    var _parse=JSON.parse;
    JSON.parse=function(){ var r=_parse.apply(this,arguments); return strip(r); };
  }catch(e){}
  try{
    var _json=Response.prototype.json;
    Response.prototype.json=function(){ return _json.apply(this,arguments).then(strip); };
  }catch(e){}
  try{
    var _ipr;
    Object.defineProperty(window,'ytInitialPlayerResponse',{configurable:true,get:function(){return _ipr;},set:function(v){_ipr=strip(v);}});
  }catch(e){}

  // 3) Kosmetik: sembunyikan sisa elemen iklan/promo.
  var css=document.createElement('style');
  css.textContent=[
    '.ad-container','.ad-div','.video-ads','.ytp-ad-module','.ytp-ad-overlay-container','.ytp-ad-image-overlay',
    '#player-ads','#masthead-ad','ytd-ad-slot-renderer','ytd-banner-promo-renderer','ytd-statement-banner-renderer',
    'ytd-in-feed-ad-layout-renderer','ytd-promoted-sparkles-web-renderer','ytd-display-ad-renderer',
    'ytm-promoted-sparkles-web-renderer','ytm-promoted-video-renderer','ytm-companion-ad-renderer',
    'ytm-ad-slot-renderer','ytm-in-feed-ad-layout-renderer','ad-slot-renderer','ytm-statement-banner-renderer',
    'ytm-mealbar-promo-renderer','ytm-upsell-dialog-renderer','ytm-app-promo-renderer','.mobile-topbar-header-banner',
    'ytm-banner-promo-renderer','.ytp-ce-element'
  ].join(',')+'{display:none !important;}';
  (document.head||document.documentElement).appendChild(css);

  // 4) Pelompat iklan darurat + anti "Lanjut menonton?" - hanya bertindak saat pemutar menandai iklan.
  var wasAd=false, savedMuted=false, savedRate=1;
  function mainVideo(){
    return document.querySelector('video.html5-main-video') || document.querySelector('#movie_player video, .html5-video-player video') || document.querySelector('video');
  }
  function clickAll(sel){
    var n=document.querySelectorAll(sel); for(var i=0;i<n.length;i++){ try{ n[i].click(); }catch(e){} }
  }
  function adTick(){
    var player=document.querySelector('#movie_player, .html5-video-player');
    var v=mainVideo();
    var ad=!!(player && player.classList.contains('ad-showing'));
    if(ad){
      clickAll('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-skip-button-container button, button.ytp-ad-skip-button-modern');
      clickAll('.ytp-ad-overlay-close-button');
      if(v){
        if(!wasAd){ savedMuted=v.muted; savedRate=v.playbackRate||1; }
        v.muted=true;
        try{ v.playbackRate=16; }catch(e){}
        if(isFinite(v.duration) && v.duration>0 && v.currentTime<v.duration-0.1){ try{ v.currentTime=v.duration; }catch(e){} }
        if(v.paused){ try{ v.play(); }catch(e){} }
      }
      wasAd=true;
    } else if(wasAd){
      wasAd=false;
      if(v){ v.muted=savedMuted; try{ v.playbackRate=savedRate; }catch(e){} }
    }
    // "Video dijeda. Lanjut menonton?" -> anggap pengguna aktif + konfirmasi otomatis.
    try{ window._lact=Date.now(); }catch(e){}
    clickAll('yt-confirm-dialog-renderer #confirm-button button, ytm-confirm-dialog-renderer .dialog-buttons button:last-child');
    return ad;
  }
  setInterval(adTick,350);
})();
""".trimIndent()
}
