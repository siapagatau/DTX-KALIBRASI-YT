package com.kendang.realpads

import android.net.Uri

/**
 * Navigasi & jembatan JS untuk mode YouTube (v1.16). Dipisah dari YouTubeAdBlock supaya SELURUH isi file ini
 * aman ada di semua varian build (termasuk "play"): tidak ada logika pemblokir iklan di sini.
 */
object YouTubeNav {
    const val HOME_URL = "https://m.youtube.com/"

    // Navigasi hanya boleh ke domain YouTube/Google yang dibutuhkan (login, consent, gambar, video).
    // Link keluar (situs lain, intent:// ke app YouTube) dibuang.
    private val ALLOWED_NAV_SUFFIXES = listOf(
        "youtube.com", "youtu.be", "google.com", "gstatic.com", "ytimg.com", "googlevideo.com",
        "googleusercontent.com", "ggpht.com", "youtube-nocookie.com", "accounts.google.com"
    )

    fun isAllowedNavigation(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "https" && scheme != "http" && scheme != "about" && scheme != "blob" && scheme != "data") return false
        if (scheme == "about" || scheme == "blob" || scheme == "data") return true
        val host = (uri.host ?: return false).lowercase()
        return ALLOWED_NAV_SUFFIXES.any { host == it || host.endsWith(".$it") }
    }
}

/**
 * Jembatan halaman YouTube <-> aplikasi: slider menit / volume / play-pause + tangkap PCM untuk rekaman.
 * Antarmuka native: window.DtxYt (lihat YouTubeState.Bridge). Perintah dari native: window.__dtx.*
 *
 * Penangkapan audio (v1.16, menggantikan ScriptProcessor 2048 + base64 yang bikin tersendat):
 *  - AudioWorklet (jalan di thread audio, TIDAK ikut macet kalau JS YouTube sedang sibuk). Cadangan
 *    ScriptProcessor 4096 bila addModule diblokir CSP.
 *  - Tiap potongan PCM membawa NOMOR FRAME di jam audio (startFrame). Sisi native menaruh potongan itu di
 *    garis waktu yang benar, jadi telat/bergerombolnya kiriman lewat jembatan tidak lagi menggeser musik
 *    (dulu posisi ditentukan waktu TIBA -> musik "telat" & bolong).
 *  - marker(waktuEpochMs): menghitung nomor frame yang BERBUNYI di speaker pada waktu itu (dipakai
 *    kalibrasi sinkron otomatis, lihat YouTubeState.calibrateSync).
 */
object YouTubeBridgeJs {
    val SCRIPT: String = """
(function(){
  if (window.__dtxInit) return; window.__dtxInit = true;

  // Prosesor AudioWorklet (dijalankan di thread audio; ditulis sebagai fungsi lalu di-toString()).
  function workletBody(){
    class DtxCap extends AudioWorkletProcessor {
      constructor(){
        super(); var self=this;
        this.cap=false; this.n=0; this.s=0; this.next=-1; this.b=new Int16Array(2048*2);
        this.port.onmessage=function(e){ if(e.data && typeof e.data.cap==='boolean'){ self.cap=e.data.cap; self.n=0; self.next=-1; } };
      }
      flush(){
        if(this.n<=0) return;
        var o=this.b.slice(0,this.n*2);
        this.port.postMessage({s:this.s,d:o.buffer},[o.buffer]);
        this.n=0;
      }
      process(inputs){
        var f0=currentFrame;
        var inp=inputs[0];
        if(!this.cap || !inp || !inp.length){ this.flush(); this.next=-1; return true; }
        if(this.n>0 && f0!==this.next) this.flush();   // lompatan jam -> potongan baru, jangan disambung paksa
        var l=inp[0], r=inp.length>1?inp[1]:l, N=l.length;
        for(var i=0;i<N;i++){
          if(this.n===0) this.s=f0+i;
          var a=l[i], c=r[i];
          a=a<-1?-1:(a>1?1:a); c=c<-1?-1:(c>1?1:c);
          this.b[this.n*2]=a<0?a*32768:a*32767;
          this.b[this.n*2+1]=c<0?c*32768:c*32767;
          if(++this.n===2048) this.flush();
        }
        this.next=f0+N;
        return true;
      }
    }
    registerProcessor('dtx-cap',DtxCap);
  }
  var WK='('+workletBody.toString()+')();';

  var volTarget=1, volSet=false, routed=false, ctx=null, gain=null, tap=null, wk=null, capOn=false, tapSeq=0;
  function mainVideo(){
    return document.querySelector('video.html5-main-video') || document.querySelector('#movie_player video, .html5-video-player video') || document.querySelector('video');
  }
  function applyVol(v){
    if(!v) return;
    try{
      if(routed && gain){ gain.gain.value=volTarget; if(v.volume!==1) v.volume=1; }
      else { var t=Math.min(1,volTarget); if(Math.abs(v.volume-t)>0.01) v.volume=t; }
    }catch(e){}
  }
  function b64(i16){
    var u8=new Uint8Array(i16.buffer,i16.byteOffset,i16.byteLength), s='', C=0x2000;
    for(var i=0;i<u8.length;i+=C){ s+=String.fromCharCode.apply(null,u8.subarray(i,i+C)); }
    return btoa(s);
  }
  function send(i16,startFrame){
    try{ window.DtxYt.pcm(b64(i16), ctx?(ctx.sampleRate|0):48000, startFrame); }catch(x){}
  }
  function ensureCtx(){
    if(ctx) return true;
    var AC=window.AudioContext||window.webkitAudioContext; if(!AC) return false;
    try{ ctx=new AC({sampleRate:48000,latencyHint:'interactive'}); }catch(e){ try{ ctx=new AC(); }catch(e2){ ctx=null; return false; } }
    return true;
  }
  function spTap(my){
    if(my!==tapSeq || !ctx || !gain) return;
    var p=ctx.createScriptProcessor(4096,2,2);
    p.onaudioprocess=function(e){
      if(!capOn) return;
      var l=e.inputBuffer.getChannelData(0), r=e.inputBuffer.numberOfChannels>1?e.inputBuffer.getChannelData(1):l;
      var n=l.length, o=new Int16Array(n*2);
      for(var i=0;i<n;i++){
        var a=l[i]<-1?-1:(l[i]>1?1:l[i]), b=r[i]<-1?-1:(r[i]>1?1:r[i]);
        o[i*2]=a<0?a*32768:a*32767; o[i*2+1]=b<0?b*32768:b*32767;
      }
      send(o, Math.round(e.playbackTime*ctx.sampleRate));
    };
    tap=p; gain.connect(p); p.connect(ctx.destination);
  }
  function attachTap(){
    if(!gain||!ctx) return;
    var my=++tapSeq;
    if(ctx.audioWorklet && window.AudioWorkletNode){
      var url=null;
      try{ url=URL.createObjectURL(new Blob([WK],{type:'application/javascript'})); }catch(e){ url=null; }
      if(url){
        ctx.audioWorklet.addModule(url).then(function(){
          if(my!==tapSeq || !gain) return;
          var n=new AudioWorkletNode(ctx,'dtx-cap',{numberOfInputs:1,numberOfOutputs:1,outputChannelCount:[2]});
          n.port.onmessage=function(e){ var d=e.data; if(d && d.d) send(new Int16Array(d.d), d.s); };
          n.port.postMessage({cap:capOn});
          wk=n; tap=n; gain.connect(n); n.connect(ctx.destination);
        }).catch(function(){ spTap(my); });
        return;
      }
    }
    spTap(my);
  }
  function route(v){
    // Rute audio lewat Web Audio HANYA saat merekam/kalibrasi (di luar itu pemutaran YouTube tidak disentuh).
    if(!v || routed || !window.DtxYt) return;
    try{
      if(!ensureCtx()) return;
      if(ctx.state!=='running'){ try{ ctx.resume(); }catch(e){} }
      if(ctx.state!=='running') return;  // belum boleh jalan -> JANGAN alihkan, supaya suara tidak hilang
      var src=ctx.createMediaElementSource(v);
      gain=ctx.createGain(); gain.gain.value=Math.min(volTarget,1.5);
      src.connect(gain); gain.connect(ctx.destination);
      routed=true; v.volume=1;
      attachTap();
    }catch(e){ routed=false; }
  }
  var lastVideo=null;
  function tick(){
    var v=mainVideo();
    if(v!==lastVideo){
      lastVideo=v; routed=false; tapSeq++;
      if(ctx){ try{ ctx.close(); }catch(e){} }
      ctx=null; gain=null; tap=null; wk=null;
      if(capOn) route(v);
    }
    if(!window.DtxYt) return;
    if(capOn && !routed) route(v);
    if(ctx && ctx.state==='suspended' && routed){ try{ ctx.resume(); }catch(e){} }
    if(volSet) applyVol(v);
    var watch=(location.pathname.indexOf('/watch')===0)||(location.pathname.indexOf('/shorts')===0)||location.hostname==='youtu.be';
    var ok=!!(v && isFinite(v.duration) && v.duration>0 && (watch || (!v.paused && !v.muted)));
    var title=(document.title||'').replace(/\s*-\s*YouTube\s*${'$'}/,'');
    var isAd=!!document.querySelector('.ad-showing');
    try{
      window.DtxYt.state(v&&isFinite(v.currentTime)?v.currentTime:0, v&&isFinite(v.duration)?v.duration:0,
        !!(v && !v.paused && !v.ended), title, isAd, ok);
    }catch(e){}
  }
  setInterval(tick,400);

  window.__dtx={
    play:function(){ var v=mainVideo(); if(v){ try{ v.play(); }catch(e){} } },
    pause:function(){ var v=mainVideo(); if(v){ try{ v.pause(); }catch(e){} } },
    seek:function(s){ var v=mainVideo(); if(v && isFinite(s)){ try{ v.currentTime=Math.max(0,s); }catch(e){} } },
    vol:function(x){ volTarget=Math.max(0,Math.min(1.5,x)); volSet=true; applyVol(mainVideo()); },
    cap:function(on){
      capOn=!!on;
      if(wk){ try{ wk.port.postMessage({cap:capOn}); }catch(e){} }
      if(on){ var v=mainVideo(); if(v){ if(ctx&&ctx.state!=='running'){ try{ ctx.resume(); }catch(e){} } route(v); } }
    },
    // Nomor frame (jam audio) yang BERBUNYI di speaker pada waktu epoch [fireEpochMs]. Dikirim balik lewat
    // DtxYt.markerFrame. Pakai getOutputTimestamp (pasangan jam audio <-> jam halaman dari perangkat keras).
    marker:function(fireEpochMs){
      try{
        if(!ctx || !routed || !capOn) return false;
        var origin=Date.now()-performance.now(), P=fireEpochMs-origin, ct, ts=null;
        try{ ts=ctx.getOutputTimestamp(); }catch(e){}
        if(ts && ts.contextTime>0 && ts.performanceTime>0){
          ct=ts.contextTime+(P-ts.performanceTime)/1000;
        } else {
          var lat=(ctx.outputLatency>0?ctx.outputLatency:0)+(ctx.baseLatency>0?ctx.baseLatency:0);
          if(!(lat>0)) lat=0.06;
          ct=ctx.currentTime-lat+(P-performance.now())/1000;
        }
        window.DtxYt.markerFrame(Math.round(ct*ctx.sampleRate), ctx.sampleRate|0);
        return true;
      }catch(e){ return false; }
    }
  };
})();
""".trimIndent()
}
