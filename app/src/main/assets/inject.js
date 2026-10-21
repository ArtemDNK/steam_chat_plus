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
    else if (c.t === 'api' && scp.api) scp.api.cmd(c);
  };

  // ---------- WebRTC tracking ----------
  var md = navigator.mediaDevices;
  if (md && typeof md.getUserMedia === 'function') {
    var origGUM = md.getUserMedia.bind(md);
    md.getUserMedia = function (constraints) {
      return origGUM(constraints).then(function (stream) {
        scp.streams.push(stream);
        stream.getAudioTracks().forEach(function (t) {
          if (scp.muted && !voiceStore()) t.enabled = false;
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
    var vs = voiceStore();
    var steamVoice = false;
    if (vs) {
      try { steamVoice = !!vs.IsAnyVoiceActive(); } catch (_) {}
      try { scp.muted = !!vs.IsMicMuted(); } catch (_) {}
    }
    var active = pcActive || micLive() || steamVoice;
    var key = active + '|' + scp.muted;
    if (key !== scp.last) {
      scp.last = key;
      post('call', { active: active, muted: scp.muted });
    }
  }
  setInterval(check, 1500);

  function voiceStore() {
    try { return window.g_FriendsUIApp && window.g_FriendsUIApp.VoiceStore; } catch (_) { return null; }
  }

  function setMuted(m) {
    var vs = voiceStore();
    if (vs && typeof vs.ToggleMicMuting === 'function') {
      // Steam's own mute: other people see the muted icon and Steam's UI stays in sync.
      try { if (!!vs.IsMicMuted() !== m) vs.ToggleMicMuting(); } catch (_) {}
      check();
      return;
    }
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

  // ---------- native UI API ----------
  // Reads Steam's own web-chat stores (g_FriendsUIApp) and mirrors them to Android.
  // Only the top-level /chat page runs it. Each section is posted only when it changes.
  if (window === window.top) (function () {
    var api = scp.api = { open: null, sent: {}, status: '', started: Date.now(), lastActivity: 0 };

    function App() { return window.g_FriendsUIApp; }
    function safe(f, d) { try { var v = f(); return v === undefined || v === null ? d : v; } catch (e) { return d; } }
    function V(o, k) {
      if (!o) return undefined;
      try { var v = o[k]; return typeof v === 'function' ? v.call(o) : v; } catch (e) { return undefined; }
    }
    function arr(x) {
      if (!x) return [];
      if (Array.isArray(x)) return x;
      try { if (typeof x.values === 'function') return Array.from(x.values()); } catch (_) {}
      try { return Array.from(x); } catch (_) { return []; }
    }
    function idOf(x) {
      if (typeof x === 'number') return x;
      if (typeof x === 'string') return Number(x) || 0;
      return Number(V(x, 'accountid') || V(x, 'GetAccountID') || V(x, 'unAccountID') || V(x, 'm_unAccountID') || 0);
    }

    function bbcode(raw) {
      var s = String(raw || ''), images = [];
      s = s.replace(/\[img\b[^\]]*?src=["']?([^"'\]\s]+)["']?[^\]]*\](?:\s*\[\/img\])?/gi, function (m, u) { images.push(u); return ''; });
      s = s.replace(/\[giphy\b[^\]]*\]([^\[]*)\[\/giphy\]/gi, function (m, id) {
        id = id.trim(); if (id) images.push('https://media.giphy.com/media/' + id + '/giphy.gif'); return '';
      });
      s = s.replace(/\[emoticon\]([^\[]*)\[\/emoticon\]/gi, ':$1:');
      s = s.replace(/\[emoticon=?["']?([^\]"']*)["']?\](?:\[\/emoticon\])?/gi, ':$1:');
      s = s.replace(/\[sticker\b[^\]]*?type=["']?([^"'\]\s]+)["']?[^\]]*\](?:\[\/sticker\])?/gi, '[стикер $1]');
      s = s.replace(/\[url=["']?([^\]"']+)["']?\]([\s\S]*?)\[\/url\]/gi, function (m, u, t) { return t && t !== u ? t + ' (' + u + ')' : u; });
      s = s.replace(/\[mention=[^\]]*\]([\s\S]*?)\[\/mention\]/gi, '$1');
      s = s.replace(/\[spoiler\]([\s\S]*?)\[\/spoiler\]/gi, '▒$1▒');
      s = s.replace(/\[\/?[a-z_]+(?:[= ][^\]]*)?\]/gi, '');
      s = s.replace(/\\\[/g, '[').replace(/\\\]/g, ']');
      s.replace(/https?:\/\/\S+\.(?:png|jpe?g|gif|webp)(?:\?\S*)?/gi, function (u) { images.push(u); return u; });
      return { text: s.trim(), images: images };
    }

    function person(p) {
      if (!p) return null;
      var per = V(p, 'persona') || {};
      var ingame = !!V(per, 'is_ingame'), online = !!V(per, 'is_online'), away = !!V(per, 'is_awayOrSnooze');
      var rich = V(p, 'current_game_rich_presence');
      return {
        id: idOf(p) || Number(V(per, 'GetAccountID') || 0),
        name: String(V(p, 'display_name') || V(per, 'm_strPlayerName') || ''),
        avatar: String(V(per, 'avatar_url_medium') || ''),
        state: ingame ? 2 : online ? (away ? 3 : 1) : 0,
        game: ingame ? String(V(per, 'GetCurrentGameName') || V(p, 'current_game_name') || '') : '',
        rich: typeof rich === 'string' ? rich : '',
        seen: Number(V(per, 'last_seen_online') || 0)
      };
    }
    function personById(id) {
      var fs = App().FriendStore;
      var p = safe(function () { return fs.GetFriendIfCached(id); }, null) ||
              safe(function () { return fs.GetPlayer(id); }, null);
      return person(p) || { id: id, name: String(id), avatar: '', state: 0, game: '', rich: '', seen: 0 };
    }

    function ready() {
      var a = App();
      return !!(a && a.FriendStore && a.ChatStore && a.VoiceStore &&
        V(a.FriendStore, 'friends_list_ready') !== false && V(a.ChatStore, 'chat_group_list_ready') !== false);
    }

    // ----- sections -----
    function secSelf() { return person(V(App().FriendStore, 'self')); }

    function secFriends() {
      return arr(V(App().FriendStore, 'all_friends')).filter(function (f) { return V(f, 'is_friend') !== false; })
        .map(person).filter(Boolean);
    }

    function secChats() {
      var cs = App().ChatStore;
      var list = safe(function () { return cs.GetRecentChats(); }, null) ||
                 safe(function () { return cs.FriendChatStore.GetAllChats(); }, []);
      return arr(list).filter(function (c) { return V(c, 'accountid_partner'); }).map(function (c) {
        var p = person(V(c, 'chat_partner')) || personById(V(c, 'accountid_partner'));
        var last = safe(function () { return c.GetLastMessage(); }, null);
        var text = last ? bbcode(V(last, 'strMessage')) : { text: String(c.m_strLastMessage || ''), images: [] };
        return {
          id: p.id, name: p.name, avatar: p.avatar, state: p.state, game: p.game,
          last: (text.text || (text.images.length ? '🖼 Изображение' : '')),
          mine: last ? last.unAccountID !== p.id : false,
          time: Number(V(c, 'time_last_message') || 0),
          unread: Number(V(c, 'unread_message_count') || 0),
          voice: !!V(c, 'BVoiceActive'),
          typing: !!V(c, 'is_friend_typing')
        };
      }).sort(function (a, b) { return b.time - a.time; });
    }

    function room(r) {
      return {
        id: String(V(r, 'GetRoomID') || ''),
        name: String(V(r, 'name') || ''),
        unread: Number(V(r, 'unread_message_count') || 0),
        voice: arr(V(r, 'voice_active_member_list')).map(idOf).filter(Boolean),
        active: !!V(r, 'BVoiceActive'),
        allowed: V(r, 'GetVoiceAllowed') !== false
      };
    }
    function secGroups() {
      var cs = App().ChatStore;
      return arr(cs.m_mapChatGroups).map(function (g) {
        var text = arr(V(g, 'textRoomList')).map(room), voice = arr(V(g, 'voiceRoomList')).map(room);
        var lastRoom = V(g, 'room_with_last_message');
        var last = lastRoom ? safe(function () { return lastRoom.GetLastMessage(); }, null) : null;
        return {
          id: String(V(g, 'GetGroupID') || ''),
          name: String(V(g, 'name') || ''),
          avatar: String(V(g, 'avatar_url_medium') || ''),
          online: Number(V(g, 'memberCountOnline') || 0),
          total: Number(V(g, 'memberCountTotal') || 0),
          unread: !!V(g, 'hasUnreadChatMessage'),
          time: Number(V(g, 'time_last_activity') || 0),
          last: last ? bbcode(V(last, 'strMessage')).text : '',
          defaultRoom: String(V(g, 'GetDefaultChatID') || ''),
          text: text, voiceRooms: voice
        };
      }).sort(function (a, b) { return b.time - a.time; });
    }

    function secVoice() {
      var a = App(), vs = a.VoiceStore, cs = a.ChatStore;
      var active = !!safe(function () { return vs.IsAnyVoiceActive(); }, false);
      var partner = Number(safe(function () { return vs.GetActiveOneOnOneVoiceChatAccountID(); }, 0)) || 0;
      var groupId = String(safe(function () { return vs.GetActiveChatRoomGroupID(); }, '') || '');
      var roomId = String(safe(function () { return vs.GetActiveVoiceChatID(); }, '') || '');
      if (groupId === '0') groupId = '';
      var title = '', subtitle = '', members = [];
      var selfId = idOf(V(a.FriendStore, 'self'));
      if (active && partner) {
        var p = personById(partner);
        title = p.name; subtitle = 'Личный звонок';
        members = [selfId, partner];
        groupId = ''; roomId = '';
      } else if (active && groupId) {
        var g = safe(function () { return cs.GetChatRoomGroup(groupId); }, null);
        var r = g && safe(function () { return g.GetChatRoom(roomId); }, null);
        title = r ? String(V(r, 'name') || '') : ''; subtitle = g ? String(V(g, 'name') || '') : '';
        members = r ? arr(V(r, 'voice_active_member_list')).map(idOf) : [];
      }
      return {
        active: active, partner: partner, groupId: groupId, roomId: roomId,
        title: title, subtitle: subtitle,
        mic: !!safe(function () { return vs.IsMicMuted(); }, false),
        out: !!safe(function () { return vs.IsOutputMuted(); }, false),
        connecting: !!safe(function () { return vs.IsAttemptingInitialConnection() || vs.IsAttemptingReconnect(); }, false),
        members: members.filter(Boolean).map(personById)
      };
    }

    function secIncoming() {
      var vs = App().VoiceStore;
      var m = safe(function () { return vs.GetOneOnOneCallsWaitingForJoinOrAccept(); }, null);
      var ids = [];
      try { if (m && typeof m.keys === 'function') ids = Array.from(m.keys()).map(Number); } catch (_) {}
      return ids.filter(function (id) {
        return id && safe(function () { return vs.BPartnerHasRequestedAndIsInOneOnOneChat(id); }, true) &&
          !safe(function () { return vs.BSelfHasAcceptedOrInitiatedOneOnOneChat(id); }, false);
      }).map(personById);
    }

    // ----- open conversation -----
    function chatFor(c) {
      var cs = App().ChatStore;
      if (c.kind === 'friend') return safe(function () { return cs.GetFriendChat(Number(c.id)); }, null);
      var g = safe(function () { return cs.GetChatRoomGroup(c.groupId); }, null);
      if (!g) return null;
      return safe(function () { return g.GetChatRoom(c.roomId || V(g, 'GetDefaultChatID')); }, null);
    }

    function secMessages() {
      var o = api.open; if (!o) return null;
      var chat = o.chat, a = App();
      var selfId = idOf(V(a.FriendStore, 'self'));
      var list = arr(V(chat, 'chat_messages')).slice(-200);
      var authors = {};
      var items = list.map(function (m) {
        var id = Number(m.unAccountID || 0);
        if (id && !authors[id]) authors[id] = personById(id);
        var server = !!V(m, 'BIsServerMessage');
        var b = bbcode(V(m, 'strMessage'));
        var echo = !!V(m, 'BIsLocalEcho');
        return {
          k: (m.rtTimestamp || 0) + '_' + (m.unOrdinal || 0) + (echo ? '_e' + b.text.length : ''),
          a: id, t: Number(m.rtTimestamp || 0), x: b.text, i: b.images,
          me: id === selfId, s: server,
          p: echo && !V(m, 'BHasServerAcknowledged'),
          f: !!V(m, 'eErrorSending'),
          d: Number(m.eDeleteState || 0) > 0
        };
      });
      var title = '', subtitle = '', avatar = '', state = 0;
      if (o.c.kind === 'friend') {
        var p = personById(Number(o.c.id));
        title = p.name; avatar = p.avatar; state = p.state;
        subtitle = V(chat, 'is_friend_typing') ? 'печатает…' : '';
      } else {
        var g = safe(function () { return App().ChatStore.GetChatRoomGroup(o.c.groupId); }, null);
        title = String(V(chat, 'name') || ''); subtitle = g ? String(V(g, 'name') || '') : '';
        avatar = g ? String(V(g, 'avatar_url_medium') || '') : '';
      }
      return {
        key: o.key, kind: o.c.kind, id: String(o.c.id || ''), groupId: String(o.c.groupId || ''), roomId: String(o.c.roomId || ''),
        title: title, subtitle: subtitle, avatar: avatar, state: state,
        voice: !!V(chat, 'BVoiceActive'), more: !!V(chat, 'IsMoreHistoryAvailable'),
        loading: Number(chat.m_nLoadingHistoryInProgressCount || 0) > 0,
        authors: authors, items: items
      };
    }

    function closeChat() {
      var o = api.open; if (!o) return;
      safe(function () { o.chat.OnDeactivate(); });
      if (o.c.kind === 'room') safe(function () { App().ChatStore.DecRefActiveChatRoomGroup(o.c.groupId); });
      api.open = null;
      delete api.sent.messages;
    }

    function openChat(c) {
      closeChat();
      if (c.kind === 'room') safe(function () { App().ChatStore.IncRefActiveChatRoomGroup(c.groupId); });
      var chat = chatFor(c);
      if (!chat) { post('api', { k: 'error', v: 'Чат не найден' }); return; }
      api.open = { c: c, chat: chat, key: c.key || '' };
      if (!scp.hidden) safe(function () { chat.OnActivate(); });
      safe(function () { if (!V(chat, 'm_bChatLogsLoaded')) chat.LoadChatLogs(); });
    }

    function escapeBB(text) { return String(text).replace(/\[/g, '\\['); }

    api.cmd = function (c) {
      var a = App(); if (!a) return;
      var vs = a.VoiceStore, cs = a.ChatStore, fs = a.FriendStore;
      switch (c.op) {
        case 'resync': api.sent = {}; break;
        case 'open': openChat(c); break;
        case 'close': closeChat(); break;
        case 'send': if (api.open && c.text) safe(function () { api.open.chat.SendChatMessage(escapeBB(c.text)); }); break;
        case 'typing': if (api.open) safe(function () { api.open.chat.OnTyping(); }); break;
        case 'more': if (api.open) safe(function () { api.open.chat.LoadMoreHistory(); }); break;
        case 'retry': break;
        case 'callFriend':
          var fc = safe(function () { return cs.GetFriendChat(Number(c.id)); }, null);
          if (fc && !V(fc, 'BVoiceActive')) safe(function () { fc.ToggleVoiceChat(); });
          break;
        case 'joinRoom':
          safe(function () { cs.IncRefActiveChatRoomGroup(c.groupId); });
          var g = safe(function () { return cs.GetChatRoomGroup(c.groupId); }, null);
          var r = g && safe(function () { return g.GetChatRoom(c.roomId); }, null);
          if (r && !V(r, 'BVoiceActive')) {
            if (typeof r.StartVoiceChat === 'function') safe(function () { r.StartVoiceChat(); });
            else safe(function () { r.ToggleVoiceChat(); });
          }
          break;
        case 'hangup': safe(function () { vs.OnUserEndVoiceChat(); }); break;
        case 'deafen': safe(function () { vs.ToggleOutputMuting(); }); break;
        case 'accept': safe(function () { vs.AcceptPartnersOneOnOneChatRequest(Number(c.id)); }); break;
        case 'reject': safe(function () { vs.OnRejectOneOnOneVoiceChatForPartner(Number(c.id)); }); break;
        case 'activity':
          // Native taps never reach the page; tell Steam the user is here so it doesn't go "Away".
          safe(function () { fs.ResetIdleState(); });
          try { document.dispatchEvent(new MouseEvent('mousemove', { bubbles: true })); } catch (_) {}
          break;
      }
      setTimeout(tick, 60);
    };

    function send(k, v) {
      var s = JSON.stringify(v);
      if (api.sent[k] === s) return;
      api.sent[k] = s;
      post('api', { k: k, v: v });
    }

    function tick() {
      var onChat = /^\/chat/.test(location.pathname);
      var st = !onChat ? 'web' : ready() ? 'ready' : 'loading';
      if (st !== api.status || !api.sent.status) { api.status = st; api.sent.status = '1'; post('api', { k: 'status', v: st }); }
      if (st !== 'ready') return;
      try {
        send('self', secSelf());
        send('friends', secFriends());
        send('chats', secChats());
        send('groups', secGroups());
        send('voice', secVoice());
        send('incoming', secIncoming());
        if (api.open) {
          if (!scp.hidden && Number(V(api.open.chat, 'unread_message_count') || 0) > 0) safe(function () { api.open.chat.OnActivate(); });
          send('messages', secMessages());
        }
      } catch (e) {
        post('api', { k: 'error', v: String(e && e.message || e) });
      }
    }
    setInterval(tick, 1000);
    setTimeout(tick, 300);
  })();

  // ---------- hello ----------
  bindBridge();
  post('hello', { top: window === window.top, href: String(location.href) });
  check();
})();
