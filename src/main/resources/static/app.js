const $ = s => document.querySelector(s);
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const initial = s => esc((s || '?').trim().charAt(0).toUpperCase());
const hhmm = t => new Date(t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
const PAGE = 30;

let token = localStorage.getItem('chat_token');
let me = JSON.parse(localStorage.getItem('chat_user') || 'null');
let rooms = new Map(), msgs = new Map(), hasMore = new Map(), online = new Set();
let activeId = null, stomp = null, subs = {}, typingTimer = null, lastTypingSent = 0;

async function api(path, method = 'GET', body) {
  const r = await fetch(path, {
    method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    body: body ? JSON.stringify(body) : undefined
  });
  const text = await r.text();
  const data = text ? JSON.parse(text) : null;
  if (r.status === 401 && token) { logout(); throw new Error('Session expired'); }
  if (!r.ok) throw new Error((data && data.message) || 'Request failed (' + r.status + ')');
  return data;
}
function toast(m, err) {
  const t = $('#toast'); t.textContent = m; t.className = 'show' + (err ? ' err' : '');
  clearTimeout(t._t); t._t = setTimeout(() => t.className = '', 3200);
}
function logout() {
  token = null; me = null; localStorage.removeItem('chat_token'); localStorage.removeItem('chat_user');
  if (stomp) { try { stomp.disconnect(); } catch (e) {} stomp = null; }
  rooms.clear(); msgs.clear(); activeId = null; subs = {};
  authView();
}

/* ---------------- auth ---------------- */
function authView() {
  let mode = 'login';
  const draw = () => {
    $('#app').innerHTML = `<div class="auth"><h1>💬 Chat</h1><div class="muted">Private and group messaging in real time</div>
      <div class="tabs"><button data-m="login" class="${mode === 'login' ? 'on' : ''}">Login</button><button data-m="register" class="${mode === 'register' ? 'on' : ''}">Register</button></div>
      <input id="un" placeholder="Username" autocomplete="username">
      ${mode === 'register' ? '<input id="dn" placeholder="Display name (optional)">' : ''}
      <input id="pw" type="password" placeholder="Password (min 6 characters)" autocomplete="current-password">
      <button id="go">${mode === 'login' ? 'Login' : 'Create account'}</button></div>`;
    document.querySelectorAll('[data-m]').forEach(b => b.onclick = () => { mode = b.dataset.m; draw(); });
    const submit = async () => {
      try {
        const body = { username: $('#un').value, password: $('#pw').value };
        if (mode === 'register') body.displayName = $('#dn').value;
        const d = await api('/api/auth/' + mode, 'POST', body);
        token = d.token; me = { userId: d.userId, username: d.username, displayName: d.displayName };
        localStorage.setItem('chat_token', token); localStorage.setItem('chat_user', JSON.stringify(me));
        chatView();
      } catch (e) { toast(e.message, true); }
    };
    $('#go').onclick = submit;
    $('#pw').onkeydown = e => { if (e.key === 'Enter') submit(); };
  };
  draw();
}

/* ---------------- chat shell ---------------- */
function chatView() {
  $('#app').innerHTML = `<div class="chat"><aside>
      <div class="me"><div class="who"><b>${esc(me.displayName)}</b><div class="muted">@${esc(me.username)}</div></div><button class="ghost" id="out">Logout</button></div>
      <div class="actions"><button id="newChat">+ Chat</button><button id="newGroup" class="ghost">+ Group</button></div>
      <div id="roomList"></div></aside>
    <main><div id="head"><span class="muted">Select a conversation or start a new one</span></div>
      <div id="msgs"><div class="empty">💬</div></div><div id="typing"></div>
      <form id="composer" style="display:none"><input id="text" placeholder="Type a message" autocomplete="off" maxlength="2000"><button>Send</button></form></main></div>`;
  $('#out').onclick = logout;
  $('#newChat').onclick = () => pickUsers({ title: 'Start a private chat', multi: false, onDone: async ([u]) => openRoomObj(await api('/api/rooms/private/' + u.id, 'POST')) });
  $('#newGroup').onclick = () => pickUsers({ title: 'Create a group', multi: true, withName: true, onDone: async (list, name) => {
    openRoomObj(await api('/api/rooms/group', 'POST', { name, memberIds: list.map(u => u.id) })); } });
  $('#composer').onsubmit = e => { e.preventDefault(); send(); };
  $('#text').oninput = () => {
    if (activeId && stomp && Date.now() - lastTypingSent > 2000) { lastTypingSent = Date.now(); stomp.send(`/app/rooms/${activeId}/typing`, {}, '{}'); }
  };
  loadRooms().then(connectWs);
}
async function loadRooms() {
  try { (await api('/api/rooms')).forEach(r => rooms.set(r.id, r)); renderRooms(); } catch (e) { toast(e.message, true); }
}
const activity = r => new Date(r.lastMessage ? r.lastMessage.createdAt : r.createdAt).getTime();
const partner = r => r.type === 'PRIVATE' ? r.members.find(m => m.userId !== me.userId) : null;
function renderRooms() {
  const list = [...rooms.values()].sort((a, b) => activity(b) - activity(a));
  $('#roomList').innerHTML = list.map(r => {
    const p = partner(r), isOn = p && online.has(p.userId);
    return `<div class="room ${r.id === activeId ? 'on' : ''}" data-id="${r.id}"><div class="avatar">${r.type === 'GROUP' ? '👥' : initial(r.name)}${isOn ? '<span class="dot"></span>' : ''}</div>
      <div class="rinfo"><div class="rname">${esc(r.name)}</div><div class="rlast">${r.lastMessage ? esc(r.lastMessage.senderName + ': ' + r.lastMessage.content) : 'No messages yet'}</div></div>
      ${r.unread > 0 ? `<span class="badge">${r.unread}</span>` : ''}</div>`;
  }).join('') || '<div class="muted" style="padding:16px">No conversations yet.</div>';
  document.querySelectorAll('.room').forEach(el => el.onclick = () => openRoom(+el.dataset.id));
}
function openRoomObj(r) { rooms.set(r.id, rooms.get(r.id) || r); subscribeRoom(r.id); openRoom(r.id); }

/* ---------------- open conversation ---------------- */
async function openRoom(id) {
  activeId = id; const r = rooms.get(id);
  $('#composer').style.display = 'flex'; $('#typing').textContent = '';
  const p = partner(r), isAdmin = r.members.some(m => m.userId === me.userId && m.role === 'ADMIN');
  $('#head').innerHTML = `<div><div class="t">${esc(r.name)}</div><div class="muted">${r.type === 'GROUP' ? r.members.map(m => esc(m.displayName)).join(', ') : (online.has(p.userId) ? 'online' : 'offline')}</div></div>
    <div>${r.type === 'GROUP' ? (isAdmin ? '<button class="ghost" id="addMember">Add member</button>' : '') + '<button class="ghost" id="leave">Leave</button>' : ''}</div>`;
  if ($('#addMember')) $('#addMember').onclick = () => pickUsers({ title: 'Add a member', multi: false, onDone: async ([u]) => {
    const upd = await api(`/api/rooms/${id}/members`, 'POST', { userId: u.id }); rooms.set(id, { ...rooms.get(id), members: upd.members }); openRoom(id); } });
  if ($('#leave')) $('#leave').onclick = async () => {
    if (!confirm('Leave this group?')) return;
    try { await api(`/api/rooms/${id}/members/me`, 'DELETE'); rooms.delete(id); activeId = null; chatView(); } catch (e) { toast(e.message, true); }
  };
  try {
    const page = await api(`/api/rooms/${id}/messages?size=${PAGE}`);
    msgs.set(id, page); hasMore.set(id, page.length === PAGE);
    renderMessages(true);
    if (r.unread > 0) { r.unread = 0; api(`/api/rooms/${id}/read`, 'POST').catch(() => {}); }
    renderRooms(); $('#text').focus();
  } catch (e) { toast(e.message, true); }
}
function msgHtml(m) {
  const mine = m.senderId === me.userId, group = rooms.get(m.roomId)?.type === 'GROUP';
  return `<div class="msg ${mine ? 'mine' : ''}">${!mine && group ? `<div class="by">${esc(m.senderName)}</div>` : ''}${esc(m.content)}<div class="ts">${hhmm(m.createdAt)}</div></div>`;
}
function renderMessages(scrollDown) {
  const box = $('#msgs'), list = msgs.get(activeId) || [];
  box.innerHTML = (hasMore.get(activeId) ? '<button class="ghost" id="older">Load older messages</button>' : '')
    + (list.length ? list.map(msgHtml).join('') : '<div class="empty">No messages yet. Say hi 👋</div>');
  if ($('#older')) $('#older').onclick = loadOlder;
  if (scrollDown) box.scrollTop = box.scrollHeight;
}
async function loadOlder() {
  const list = msgs.get(activeId); if (!list.length) return;
  const page = await api(`/api/rooms/${activeId}/messages?size=${PAGE}&before=${list[0].id}`);
  msgs.set(activeId, [...page, ...list]); hasMore.set(activeId, page.length === PAGE);
  const box = $('#msgs'), prev = box.scrollHeight; renderMessages(false); box.scrollTop = box.scrollHeight - prev;
}
function send() {
  const input = $('#text'), text = input.value.trim();
  if (!text || !activeId) return;
  if (!stomp) return toast('Not connected, retrying…', true);
  stomp.send(`/app/rooms/${activeId}/send`, {}, JSON.stringify({ content: text }));
  input.value = '';
}

/* ---------------- realtime ---------------- */
function connectWs() {
  if (!token) return;
  const c = Stomp.over(new SockJS('/ws')); c.debug = null;
  c.connect({ Authorization: 'Bearer ' + token }, () => {
    stomp = c; subs = {};
    c.subscribe('/topic/presence', f => { const e = JSON.parse(f.body); e.online ? online.add(e.userId) : online.delete(e.userId); renderRooms(); });
    c.subscribe('/user/queue/errors', f => toast(JSON.parse(f.body).error, true));
    c.subscribe('/user/queue/events', f => {
      const e = JSON.parse(f.body);
      if (e.type === 'ROOM_ADDED') { rooms.set(e.data.id, e.data); subscribeRoom(e.data.id); renderRooms(); toast('You were added to ' + e.data.name); }
    });
    rooms.forEach((_, id) => subscribeRoom(id));
    api('/api/users/online').then(ids => { online = new Set(ids); renderRooms(); });
  }, () => { stomp = null; if (token) setTimeout(connectWs, 3000); });
}
function subscribeRoom(id) {
  if (!stomp || subs[id]) return;
  subs[id] = [stomp.subscribe(`/topic/rooms/${id}`, f => onMessage(JSON.parse(f.body))),
              stomp.subscribe(`/topic/rooms/${id}/typing`, f => onTyping(id, JSON.parse(f.body)))];
}
function onMessage(m) {
  const r = rooms.get(m.roomId); if (!r) return loadRooms();
  r.lastMessage = m;
  if (m.roomId === activeId) {
    const list = msgs.get(activeId) || [];
    if (!list.some(x => x.id === m.id)) list.push(m);
    msgs.set(activeId, list); renderMessages(true); $('#typing').textContent = '';
    if (m.senderId !== me.userId) api(`/api/rooms/${activeId}/read`, 'POST').catch(() => {});
  } else if (m.senderId !== me.userId) r.unread = (r.unread || 0) + 1;
  renderRooms();
}
function onTyping(roomId, e) {
  if (e.userId === me.userId || roomId !== activeId) return;
  $('#typing').textContent = e.username + ' is typing…';
  clearTimeout(typingTimer); typingTimer = setTimeout(() => $('#typing').textContent = '', 3000);
}

/* ---------------- user picker ---------------- */
function pickUsers({ title, multi, withName, onDone }) {
  const picked = new Map();
  const el = document.createElement('div'); el.className = 'modal';
  el.innerHTML = `<div class="box"><b>${esc(title)}</b>${withName ? '<input id="gname" placeholder="Group name">' : ''}
    <input id="usearch" placeholder="Search users by name"><div class="list" id="ulist"></div>
    <div class="muted" id="sel"></div><div style="display:flex;gap:8px;justify-content:flex-end"><button class="ghost" id="cancel">Cancel</button>${multi ? '<button id="done">Create</button>' : ''}</div></div>`;
  document.body.appendChild(el);
  const close = () => el.remove();
  const finish = async () => {
    try { await onDone([...picked.values()], withName ? el.querySelector('#gname').value : undefined); close(); } catch (e) { toast(e.message, true); }
  };
  const search = async () => {
    const users = await api('/api/users?q=' + encodeURIComponent(el.querySelector('#usearch').value));
    el.querySelector('#ulist').innerHTML = users.map(u => `<div class="urow ${picked.has(u.id) ? 'sel' : ''}" data-id="${u.id}"><div class="avatar">${initial(u.displayName)}</div><div><b>${esc(u.displayName)}</b><div class="muted">@${esc(u.username)}</div></div></div>`).join('') || '<div class="muted">No users found</div>';
    el.querySelectorAll('.urow').forEach(row => row.onclick = () => {
      const u = users.find(x => x.id === +row.dataset.id);
      if (!multi) { picked.set(u.id, u); return finish(); }
      picked.has(u.id) ? picked.delete(u.id) : picked.set(u.id, u); row.classList.toggle('sel');
      el.querySelector('#sel').textContent = picked.size + ' selected';
    });
  };
  el.querySelector('#usearch').oninput = search;
  el.querySelector('#cancel').onclick = close;
  if (multi) el.querySelector('#done').onclick = finish;
  search();
}

if (token && me) chatView(); else authView();
