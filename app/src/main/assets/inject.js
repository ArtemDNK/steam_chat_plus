/*
 * Steam Chat+ page hooks. Runs at document start in steamcommunity.com frames,
 * before Steam's own scripts. Kotlin prepends: window.__scpConfig = {...};
 *
 *  - tracks WebRTC (RTCPeerConnection + getUserMedia) to know when a voice call is live
 *  - hardware mute: flips enabled on captured microphone tracks
 *  - window.Notification polyfill -> native Android notifications
 *  - document.hidden / visibilityState follow the Android activity, so Steam
 *    notifies only when the app is in the background (like a background tab)
 *  - viewport width + custom CSS from settings
 */
(function () {
  'use strict';
  if (window.__scp) return;

  var cfg = window.__scpConfig || {};
  var scp = window.__scp = {
    streams: [],
    pcs: [],
    muted: false,
    hidden: false,
    notifs: {},
    nid: 0,
    last: ''
  };

  // ---------- bridge ----------
  function bridge() { return window.SCPBridge; }
  function post(type, data) {
    var b = bridge();
    if (!b) return;
    var msg = { type: type };
    if (data) for (var k in data) msg[k] = data[k];
    try { b.postMessage(JSON.stringify(msg)); } catch (e) { /* ignore */ }
  }
  function bindBridge() {
    var b = bridge();
    if (!b || b.__scpBound) return;
    try {
      if (typeof b.addEventListener === 'function') {
        b.addEventListener('message', function (e) {
          try { scp.cmd(JSON.parse(e.data)); } catch (_) {}
        });
        b.__scpBound = true;
      }
    } catch (_) {}
  }

  scp.cmd = function (c) {
    if (!c) return;
    if (c.t === 'hidden') setHidden(!!c.v);
    else if (c.t === 'mute') setMuted(!!c.v);
    else if (c.t === 'click') notifClick(c.id);
  };

  // ---------- WebRTC tracking ----------
  var md = navigator.mediaDevices;
  if (md && typeof md.getUserMedia === 'function') {
    var origGUM = md.getUserMedia.bind(md);
    md.getUserMedia = function (constraints) {
      return origGUM(constraints).then(function (stream) {
        scp.streams.push(stream);
        stream.getAudioTracks().forEach(function (t) {
          if (scp.muted) t.enabled = false;
          t.addEventListener('ended', check);
        });
        check();
        return stream;
      });
    };
  }

  if (window.MediaStreamTrack && MediaStreamTrack.prototype.stop) {
    var origStop = MediaStreamTrack.prototype.stop;
    MediaStreamTrack.prototype.stop = function () {
      var r = origStop.apply(this, arguments);
      setTimeout(check, 0);
      return r;
    };
  }

  function trackPc(pc) {
    scp.pcs.push(pc);
    var upd = function () { check(); };
    pc.addEventListener('connectionstatechange', upd);
    pc.addEventListener('iceconnectionstatechange', upd);
    pc.addEventListener('signalingstatechange', upd);
  }
  ['RTCPeerConnection', 'webkitRTCPeerConnection'].forEach(function (name) {
    var Orig = window[name];
    if (typeof Orig !== 'function' || Orig.__scpWrapped) return;
    var P = new Proxy(Orig, {
      construct: function (target, args, newTarget) {
        var pc = Reflect.construct(target, args, newTarget);
        try { trackPc(pc); } catch (_) {}
        return pc;
      }
    });
    try { Object.defineProperty(P, '__scpWrapped', { value: true }); } catch (_) {}
    window[name] = P;
  });

  function pcAlive(pc) {
    var s = pc.connectionState;
    if (s === 'closed' || s === 'failed') return false;
    if (pc.signalingState === 'closed') return false;
    if (s === 'connected' || s === 'connecting') return true;
    var i = pc.iceConnectionState;
    return i === 'checking' || i === 'connected' || i === 'completed';
  }

  function micLive() {
    var live = false;
    scp.streams = scp.streams.filter(function (s) {
      var tracks = s.getAudioTracks().filter(function (t) { return t.readyState === 'live'; });
      if (tracks.length) live = true;
      return tracks.length > 0;
    });
    return live;
  }

  function check() {
    scp.pcs = scp.pcs.filter(function (pc) {
      return pc.connectionState !== 'closed' && pc.signalingState !== 'closed';
    });
    var pcActive = scp.pcs.some(pcAlive);
    var active = pcActive || micLive();
    var key = active + '|' + scp.muted;
    if (key !== scp.last) {
      scp.last = key;
      post('call', { active: active, muted: scp.muted });
    }
  }
  setInterval(check, 1500);

  function setMuted(m) {
    scp.muted = m;
    scp.streams.forEach(function (s) {
      s.getAudioTracks().forEach(function (t) { t.enabled = !m; });
    });
    check();
  }

  // ---------- visibility ----------
  try {
    Object.defineProperty(Document.prototype, 'hidden', {
      configurable: true, get: function () { return scp.hidden; }
    });
    Object.defineProperty(Document.prototype, 'visibilityState', {
      configurable: true, get: function () { return scp.hidden ? 'hidden' : 'visible'; }
    });
    var origHasFocus = Document.prototype.hasFocus;
    Document.prototype.hasFocus = function () {
      return scp.hidden ? false : origHasFocus.call(this);
    };
  } catch (_) {}

  function setHidden(h) {
    if (h === scp.hidden) return;
    scp.hidden = h;
    try {
      document.dispatchEvent(new Event('visibilitychange'));
      window.dispatchEvent(new Event(h ? 'blur' : 'focus'));
    } catch (_) {}
  }

  // ---------- Notification polyfill ----------
  function SCPNotification(title, opts) {
    if (!(this instanceof SCPNotification)) throw new TypeError("Failed to construct 'Notification'");
    opts = opts || {};
    var self = this;
    var et = document.createDocumentFragment(); // cheap EventTarget
    this._et = et;
    this.title = String(title == null ? '' : title);
    this.body = String(opts.body == null ? '' : opts.body);
    this.icon = String(opts.icon || '');
    this.tag = String(opts.tag || '');
    this.data = opts.data;
    this.silent = !!opts.silent;
    this.onclick = this.onclose = this.onshow = this.onerror = null;
    this._id = ++scp.nid;
    scp.notifs[this._id] = this;
    var ids = Object.keys(scp.notifs);
    if (ids.length > 300) delete scp.notifs[ids[0]];
    post('notify', { id: this._id, title: this.title, body: this.body, icon: this.icon, tag: this.tag, silent: this.silent });
    setTimeout(function () { self._fire('show'); }, 0);
  }
  SCPNotification.prototype.addEventListener = function (t, f, o) { this._et.addEventListener(t, f, o); };
  SCPNotification.prototype.removeEventListener = function (t, f, o) { this._et.removeEventListener(t, f, o); };
  SCPNotification.prototype.dispatchEvent = function (e) { return this._et.dispatchEvent(e); };
  SCPNotification.prototype._fire = function (type) {
    var e = new Event(type, { cancelable: true });
    try { var h = this['on' + type]; if (typeof h === 'function') h.call(this, e); } catch (err) { setTimeout(function () { throw err; }); }
    this._et.dispatchEvent(e);
    return e;
  };
  SCPNotification.prototype.close = function () {
    if (!scp.notifs[this._id]) return;
    delete scp.notifs[this._id];
    post('notifyClose', { id: this._id, tag: this.tag });
    this._fire('close');
  };
  Object.defineProperty(SCPNotification, 'permission', { get: function () { return 'granted'; } });
  Object.defineProperty(SCPNotification, 'maxActions', { get: function () { return 0; } });
  SCPNotification.requestPermission = function (cb) {
    post('notifyPermission');
    if (typeof cb === 'function') setTimeout(function () { cb('granted'); });
    return Promise.resolve('granted');
  };

  function notifClick(id) {
    var n = scp.notifs[id];
    try { window.focus(); } catch (_) {}
    if (n) n._fire('click');
  }

  try {
    Object.defineProperty(window, 'Notification', { configurable: true, writable: true, value: SCPNotification });
  } catch (_) { window.Notification = SCPNotification; }

  // ---------- layout ----------
  function applyLayout() {
    var head = document.head || document.documentElement;
    if (!head) return;
    if (window === window.top) {
      var width = parseInt(cfg.pageWidth, 10) || 0;
      var content = (width > 0 ? 'width=' + width : 'width=device-width, initial-scale=1') +
        ', minimum-scale=0.25, maximum-scale=5, user-scalable=yes, viewport-fit=cover';
      var metas = document.querySelectorAll('meta[name="viewport"]');
      for (var i = 0; i < metas.length; i++) metas[i].parentNode.removeChild(metas[i]);
      var m = document.createElement('meta');
      m.name = 'viewport';
      m.content = content;
      head.appendChild(m);
    }
    if (!document.getElementById('scp-style')) {
      var st = document.createElement('style');
      st.id = 'scp-style';
      st.textContent =
        'html,body{overscroll-behavior:none;-webkit-tap-highlight-color:transparent}\n' +
        (cfg.css || '');
      head.appendChild(st);
    }
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', applyLayout);
  } else {
    applyLayout();
  }

  // ---------- hello ----------
  bindBridge();
  post('hello', { top: window === window.top, href: String(location.href) });
  check();
})();
