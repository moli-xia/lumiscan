'use strict';
/* 光点扫描管理后台 — no build step, no external dependencies (served under a strict CSP). */
(() => {
  const app = document.getElementById('app');
  const modalRoot = document.getElementById('modal-root');
  const toastEl = document.getElementById('toast');

  const state = { admin: null, features: [], groups: [], openFeedback: 0 };
  const featureLabel = key => (state.features.find(f => f.key === key) || {}).label || key;

  // ---------------------------------------------------------------- utils

  const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const pad = n => String(n).padStart(2, '0');
  const fmtTime = ms => {
    if (!ms) return '—';
    const d = new Date(ms);
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  };
  const fmtDate = ms => (ms ? fmtTime(ms).slice(0, 10) : '—');
  const fmtNum = n => (n == null || n === '' ? '—' : Number(n).toLocaleString('zh-CN'));
  const fmtYuan = v => '¥' + (v === 0 ? '0' : v < 0.01 ? v.toFixed(4) : v.toFixed(2));
  const fmtTokens = n => {
    n = Number(n || 0);
    if (n < 0) return '不限';
    if (n >= 1e8) return (n / 1e8).toFixed(2).replace(/\.?0+$/, '') + ' 亿';
    if (n >= 1e4) return (n / 1e4).toFixed(1).replace(/\.0$/, '') + ' 万';
    return fmtNum(n);
  };
  const limitText = n => (n == null ? '—' : Number(n) < 0 ? '不限' : fmtNum(n));
  const relTime = ms => {
    if (!ms) return '—';
    const s = (Date.now() - ms) / 1000;
    if (s < 60) return '刚刚';
    if (s < 3600) return `${Math.floor(s / 60)} 分钟前`;
    if (s < 86400) return `${Math.floor(s / 3600)} 小时前`;
    if (s < 86400 * 30) return `${Math.floor(s / 86400)} 天前`;
    return fmtDate(ms);
  };
  // Membership expiry is shown and edited as an inclusive end date ("有效期至").
  const expiryToDate = ms => (ms ? fmtDate(ms - 1) : '');
  const dateToExpiry = str => (str ? new Date(str + 'T00:00:00').getTime() + 86400000 : null);
  const debounce = (fn, ms) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; };
  const loading = '<div class="empty">加载中…</div>';

  async function api(method, path, body) {
    const opts = { method, headers: { 'X-Lumi-Admin': '1' }, credentials: 'same-origin' };
    if (body !== undefined) {
      opts.headers['Content-Type'] = 'application/json';
      opts.body = JSON.stringify(body);
    }
    const res = await fetch('/admin/api' + path, opts);
    let data = null;
    try { data = await res.json(); } catch (_) { /* empty body */ }
    if (res.status === 401 && path !== '/login') {
      state.admin = null;
      closeAllModals();
      render();
      throw new Error((data && data.message) || '请先登录');
    }
    if (res.status === 403 && data && data.error === 'must_change_password') {
      state.admin.mustChangePassword = true;
      render();
    }
    if (!res.ok) throw new Error((data && data.message) || `请求失败（HTTP ${res.status}）`);
    return data;
  }

  let toastTimer;
  function toast(msg, kind) {
    toastEl.textContent = msg;
    toastEl.className = 'toast show' + (kind === 'err' ? ' err' : '');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { toastEl.className = 'toast'; }, kind === 'err' ? 4000 : 2200);
  }
  const fail = e => toast(e.message || String(e), 'err');

  // ---------------------------------------------------------------- modal

  function closeAllModals() { modalRoot.innerHTML = ''; }

  /**
   * Opens a dialog. onOk(bodyEl) may return false to keep it open; thrown errors are toasted.
   * Returns { body, close }.
   */
  function openModal({ title, body, okText = '保存', cancelText = '取消', danger = false, wide = false, onOk = null, noFooter = false }) {
    const wrap = document.createElement('div');
    wrap.className = 'modal-backdrop';
    wrap.innerHTML = `
      <div class="modal ${wide ? 'wide' : ''}" role="dialog" aria-modal="true" aria-label="${esc(title)}">
        <div class="modal-head"><h3>${esc(title)}</h3><button class="icon-btn" data-close aria-label="关闭">×</button></div>
        <div class="modal-body">${body}</div>
        ${noFooter ? '' : `<div class="modal-foot">
          <button data-close>${esc(cancelText)}</button>
          ${onOk ? `<button class="${danger ? 'btn-danger-solid' : 'btn-primary'}" data-ok>${esc(okText)}</button>` : ''}
        </div>`}
      </div>`;
    modalRoot.appendChild(wrap);
    const bodyEl = wrap.querySelector('.modal-body');
    const close = () => { wrap.remove(); document.removeEventListener('keydown', onKey); };
    const onKey = e => { if (e.key === 'Escape' && modalRoot.lastElementChild === wrap) close(); };
    document.addEventListener('keydown', onKey);
    const submit = async () => {
      const btn = wrap.querySelector('[data-ok]');
      if (!btn || btn.disabled) return;
      btn.disabled = true;
      try {
        const r = await onOk(bodyEl);
        if (r !== false) close();
      } catch (e) { fail(e); } finally { btn.disabled = false; }
    };
    wrap.addEventListener('mousedown', e => { if (e.target === wrap) wrap.dataset.down = '1'; });
    wrap.addEventListener('click', e => {
      if (e.target === wrap && wrap.dataset.down) { close(); return; }
      delete wrap.dataset.down;
      if (e.target.closest('[data-close]')) close();
      else if (e.target.closest('[data-ok]')) submit();
    });
    bodyEl.querySelectorAll('form').forEach(f => f.addEventListener('submit', e => { e.preventDefault(); submit(); }));
    const first = bodyEl.querySelector('input:not([type=hidden]):not([readonly]), select, textarea');
    if (first) setTimeout(() => first.focus(), 30);
    return { body: bodyEl, close };
  }

  function confirmBox(title, message, okText = '确定', danger = true) {
    return new Promise(resolve => {
      let done = false;
      const m = openModal({
        title, body: `<p>${message}</p>`, okText, danger,
        onOk: () => { done = true; resolve(true); },
      });
      const observer = new MutationObserver(() => {
        if (!m.body.isConnected) { observer.disconnect(); if (!done) resolve(false); }
      });
      observer.observe(modalRoot, { childList: true });
    });
  }

  function showSecret(title, label, value) {
    openModal({
      title,
      body: `<p class="muted">${esc(label)}</p><div class="secret mono">${esc(value)}</div><p class="muted small">关闭后将无法再次查看，请立即复制并安全地告知对方。</p>`,
      cancelText: '我已记下',
    });
  }

  /** Collects named fields: checkboxes with the same name become arrays, single checkboxes booleans. */
  function formData(root) {
    const out = {};
    const multi = {};
    root.querySelectorAll('[name]').forEach(el => {
      if (el.type === 'checkbox') {
        if (el.dataset.multi) { (multi[el.name] = multi[el.name] || []); if (el.checked) multi[el.name].push(el.value); }
        else out[el.name] = el.checked;
      } else out[el.name] = el.value;
    });
    return Object.assign(out, multi);
  }

  function pager(total, page, size) {
    const pages = Math.max(1, Math.ceil(total / size));
    return `<div class="pager"><span>共 ${fmtNum(total)} 条 · 第 ${page} / ${pages} 页</span>
      <div class="row"><button data-page="${page - 1}" ${page <= 1 ? 'disabled' : ''}>上一页</button>
      <button data-page="${page + 1}" ${page >= pages ? 'disabled' : ''}>下一页</button></div></div>`;
  }

  function quotaBar(used, limit) {
    if (limit < 0) return '<div class="track ok"><div style="width:0"></div></div>';
    const pct = limit === 0 ? 100 : Math.min(100, Math.round((used / limit) * 100));
    const cls = pct >= 100 ? 'danger' : pct >= 80 ? 'warn' : 'ok';
    return `<div class="track ${cls}"><div style="width:${pct}%"></div></div>`;
  }

  async function ensureGroups(force) {
    if (force || !state.groups.length) state.groups = await api('GET', '/groups');
    return state.groups;
  }
  const groupOptions = (selected, withAll) =>
    (withAll ? '<option value="">全部分组</option>' : '') +
    state.groups.map(g => `<option value="${g.id}" ${String(g.id) === String(selected) ? 'selected' : ''}>${esc(g.name)}</option>`).join('');

  // ---------------------------------------------------------------- shell & routing

  const pages = [
    { id: 'dashboard', title: '概览', render: pageDashboard },
    { id: 'users', title: '用户管理', render: pageUsers },
    { id: 'groups', title: '分组与配额', render: pageGroups },
    { id: 'ai', title: 'AI 调用记录', render: pageAiLogs },
    { id: 'feedback', title: '意见反馈', render: pageFeedback },
    { id: 'settings', title: '系统设置', render: pageSettings },
    { id: 'admins', title: '管理员', render: pageAdmins, superOnly: true },
    { id: 'audit', title: '操作日志', render: pageAudit },
  ];

  const currentPage = () => {
    const id = location.hash.replace(/^#\/?/, '').split('?')[0] || 'dashboard';
    return pages.find(p => p.id === id && (!p.superOnly || state.admin.role === 'super')) || pages[0];
  };

  window.addEventListener('hashchange', () => { if (state.admin && !state.admin.mustChangePassword) renderPage(); });

  function render() {
    closeAllModals();
    if (!state.admin) return renderLogin();
    if (state.admin.mustChangePassword) return renderForceChange();
    renderShell();
    renderPage();
  }

  function renderShell() {
    const roleLabel = state.admin.role === 'super' ? '超级管理员' : '运营';
    app.innerHTML = `
      <div class="shell">
        <aside class="side">
          <div class="brand"><span class="logo">光</span><div><b>光点扫描</b><small>管理后台</small></div></div>
          <nav class="nav" id="nav"></nav>
        </aside>
        <div class="main">
          <header class="top">
            <h1 id="page-title"></h1>
            <div class="row"><span class="who">${esc(state.admin.username)} · ${roleLabel}</span>
              <button data-act="password">修改密码</button><button data-act="logout">退出</button></div>
          </header>
          <section class="content" id="content"></section>
        </div>
      </div>`;
    app.querySelector('[data-act=password]').onclick = changePasswordDialog;
    app.querySelector('[data-act=logout]').onclick = async () => {
      try { await api('POST', '/logout'); } catch (_) { /* ignore */ }
      state.admin = null;
      render();
    };
  }

  function renderNav() {
    const cur = currentPage();
    document.getElementById('nav').innerHTML = pages
      .filter(p => !p.superOnly || state.admin.role === 'super')
      .map(p => `<a href="#/${p.id}" class="${p.id === cur.id ? 'active' : ''}">${p.title}${p.id === 'feedback' && state.openFeedback ? `<span class="count">${state.openFeedback}</span>` : ''}</a>`)
      .join('');
  }

  let renderSeq = 0;
  async function renderPage() {
    const p = currentPage();
    renderNav();
    document.getElementById('page-title').textContent = p.title;
    document.title = `${p.title} · 光点扫描管理后台`;
    const el = document.getElementById('content');
    const seq = ++renderSeq;
    el.innerHTML = loading;
    try {
      await p.render(el, () => seq === renderSeq);
    } catch (e) {
      if (seq === renderSeq) el.innerHTML = `<div class="card empty">加载失败：${esc(e.message)}</div>`;
    }
  }

  function renderLogin() {
    document.title = '登录 · 光点扫描管理后台';
    app.innerHTML = `
      <div class="login-wrap"><form class="login-card" id="login-form" autocomplete="on">
        <div class="brand"><span class="logo">光</span><div><b>光点扫描</b><small>管理后台</small></div></div>
        <label class="field"><span>账号</span><input name="username" autocomplete="username" required></label>
        <label class="field"><span>密码</span><input name="password" type="password" autocomplete="current-password" required></label>
        <div class="error-text" id="login-error"></div>
        <button class="btn-primary btn-block">登录</button>
        <p class="hint">如果浏览器提示"连接不是私密连接"，是因为服务器使用自签名证书。确认地址是你自己的服务器后选择继续访问即可，连接仍是加密的。</p>
      </form></div>`;
    const form = document.getElementById('login-form');
    form.querySelector('input').focus();
    form.addEventListener('submit', async e => {
      e.preventDefault();
      const btn = form.querySelector('button');
      btn.disabled = true;
      try {
        await api('POST', '/login', formData(form));
        await boot();
      } catch (err) {
        document.getElementById('login-error').textContent = err.message;
      } finally { btn.disabled = false; }
    });
  }

  function renderForceChange() {
    app.innerHTML = `
      <div class="login-wrap"><form class="login-card" id="pw-form">
        <div class="brand"><span class="logo">光</span><div><b>请修改初始密码</b><small>${esc(state.admin.username)}</small></div></div>
        <label class="field"><span>当前密码</span><input name="oldPassword" type="password" autocomplete="current-password" required></label>
        <label class="field"><span>新密码</span><input name="newPassword" type="password" autocomplete="new-password" required minlength="10"><small>至少 10 位，需同时包含字母和数字</small></label>
        <label class="field"><span>再次输入新密码</span><input name="confirm" type="password" autocomplete="new-password" required></label>
        <div class="error-text" id="pw-error"></div>
        <button class="btn-primary btn-block">保存并进入后台</button>
      </form></div>`;
    const form = document.getElementById('pw-form');
    form.addEventListener('submit', async e => {
      e.preventDefault();
      const d = formData(form);
      const errEl = document.getElementById('pw-error');
      if (d.newPassword !== d.confirm) { errEl.textContent = '两次输入的新密码不一致'; return; }
      try {
        await api('POST', '/me/password', { oldPassword: d.oldPassword, newPassword: d.newPassword });
        state.admin.mustChangePassword = false;
        toast('密码已修改');
        render();
      } catch (err) { errEl.textContent = err.message; }
    });
  }

  function changePasswordDialog() {
    openModal({
      title: '修改密码',
      body: `<form>
        <label class="field"><span>当前密码</span><input name="oldPassword" type="password" autocomplete="current-password" required></label>
        <label class="field"><span>新密码</span><input name="newPassword" type="password" autocomplete="new-password" required><small>至少 10 位，需同时包含字母和数字。修改后其他已登录的浏览器会退出。</small></label>
        <label class="field"><span>再次输入新密码</span><input name="confirm" type="password" autocomplete="new-password" required></label></form>`,
      onOk: async body => {
        const d = formData(body);
        if (d.newPassword !== d.confirm) throw new Error('两次输入的新密码不一致');
        await api('POST', '/me/password', { oldPassword: d.oldPassword, newPassword: d.newPassword });
        toast('密码已修改');
      },
    });
  }

  async function boot() {
    try {
      const me = await api('GET', '/me');
      state.admin = me.admin;
      state.features = me.features;
    } catch (_) {
      state.admin = null;
    }
    render();
  }

  // ---------------------------------------------------------------- dashboard

  function barChart(rows, key, alt) {
    const max = Math.max(1, ...rows.map(r => r[key]));
    // Day of month only, with the month on the first bar and on the 1st, so 14 labels fit on a phone.
    const label = (day, i) => {
      const [, m, d] = day.split('-').map(Number);
      return i === 0 || d === 1 ? `${m}/${d}` : String(d);
    };
    return `<div class="bars">${rows.map((r, i) => `
      <div class="bar-col" title="${esc(r.day)}：${fmtNum(r[key])}">
        <div class="bar-val">${r[key] ? fmtTokens(r[key]) : ''}</div>
        <div class="bar ${alt ? 'alt' : ''}" style="height:${Math.round((r[key] / max) * 100)}%"></div>
        <div class="bar-label">${label(r.day, i)}</div>
      </div>`).join('')}</div>`;
  }

  async function pageDashboard(el, alive) {
    const d = await api('GET', '/dashboard');
    if (!alive()) return;
    state.openFeedback = d.openFeedback;
    renderNav();
    const alerts = [];
    if (!d.ai.configured) alerts.push('<div class="alert warn">AI 服务尚未配置：App 里的 AI 总结、问答、翻译、拍题解答都会提示"AI 服务尚未配置"。<a href="#/settings">去填写 API Key</a></div>');
    if (d.retiredModels && d.retiredModels.length) alerts.push(`<div class="alert warn">百炼已下线或无权调用以下模型：${d.retiredModels.map(m => `${esc(m.label)}（${esc(m.model)}）`).join('、')}。服务器会临时改用备用模型，请尽快在系统设置里更换。<a href="#/settings">去设置</a></div>`);
    if (!d.registrationOpen) alerts.push('<div class="alert info">当前已关闭新用户注册。<a href="#/settings">修改</a></div>');
    if (d.openFeedback) alerts.push(`<div class="alert info">有 ${d.openFeedback} 条意见反馈待处理。<a href="#/feedback">去处理</a></div>`);
    const totalUsers = Math.max(1, d.users.total);
    el.innerHTML = `
      ${alerts.join('')}
      <div class="stats">
        <div class="stat"><div class="label">注册用户</div><div class="value">${fmtNum(d.users.total)}</div><div class="sub">今日新增 ${fmtNum(d.users.today)}</div></div>
        <div class="stat"><div class="label">今日活跃</div><div class="value">${fmtNum(d.users.activeToday)}</div><div class="sub">近 7 日 ${fmtNum(d.users.active7d)}</div></div>
        <div class="stat"><div class="label">今日 AI 对话</div><div class="value">${fmtNum(d.ai.todayRequests)}</div><div class="sub">失败 ${fmtNum(d.ai.todayErrors)} · 超额拦截 ${fmtNum(d.ai.todayRejected)}</div></div>
        <div class="stat"><div class="label">今日 Tokens</div><div class="value">${fmtTokens(d.ai.todayTokens)}</div><div class="sub">本月 ${fmtTokens(d.ai.monthTokens)}</div></div>
        <div class="stat"><div class="label">今日 AI 图像 / 识别</div><div class="value">${fmtNum(d.ai.todayImages)} / ${fmtNum(d.ai.todayOcr)}</div><div class="sub">图像处理次数 / 识别页数</div></div>
        <div class="stat"><div class="label">本月模型费用（估算）</div><div class="value">${fmtYuan(d.cost.month)}</div><div class="sub">今日 ${fmtYuan(d.cost.today)} · 以百炼账单为准</div></div>
        <div class="stat"><div class="label">封禁账号</div><div class="value">${fmtNum(d.users.banned)}</div><div class="sub">模型 ${esc(d.ai.textModel)} / ${esc(d.ai.visionModel)}</div></div>
      </div>
      ${d.cost.byModel.length ? `<div class="card"><h3>本月模型费用明细（估算） <a class="small" href="#/settings">修改单价</a></h3>
        <table><thead><tr><th>模型</th><th class="right">成功调用</th><th class="right">Tokens（入 / 出）</th><th class="right">费用</th></tr></thead><tbody>
          ${d.cost.byModel.map(m => `<tr><td><code>${esc(m.model)}</code>${m.priced ? '' : ' <span class="badge orange">未设单价</span>'}</td><td class="right">${fmtNum(m.calls)}</td>
            <td class="right">${m.promptTokens || m.completionTokens ? `${fmtTokens(m.promptTokens)} / ${fmtTokens(m.completionTokens)}` : '—'}</td><td class="right">${fmtYuan(m.cost)}</td></tr>`).join('')}
        </tbody></table>
        <div class="muted small">按调用记录 × 系统设置里的模型单价估算；失败的调用不计费。图像类每次约 0.14–0.2 元，文字识别每页约 0.002 元。</div></div>` : ''}
      <div class="two-col">
        <div class="card"><h3>近 14 天 AI 调用次数</h3>${barChart(d.trend, 'aiRequests')}</div>
        <div class="card"><h3>近 14 天新增用户</h3>${barChart(d.trend, 'newUsers', true)}</div>
      </div>
      <div class="two-col">
        <div class="card"><h3>今日 AI 用量排行</h3>
          ${d.topUsers.length ? `<table><thead><tr><th>用户</th><th class="right">次数</th><th class="right">Tokens</th></tr></thead><tbody>
            ${d.topUsers.map(u => `<tr><td><a href="#/users" data-user="${u.id}">${esc(u.username)}</a> <span class="muted small">${esc(u.nickname)}</span></td>
              <td class="right">${fmtNum(u.ai_requests)}</td><td class="right">${fmtTokens(u.ai_tokens)}</td></tr>`).join('')}
          </tbody></table>` : '<div class="empty">今天还没有 AI 调用</div>'}
        </div>
        <div class="card"><h3>用户分组分布 <a class="small" href="#/groups">管理分组</a></h3>
          ${d.groups.map(g => `<div class="dist-row"><span>${esc(g.name)}</span><div class="track"><div style="width:${Math.round((g.users / totalUsers) * 100)}%"></div></div><span class="right">${fmtNum(g.users)}</span></div>`).join('')}
        </div>
      </div>`;
    el.querySelectorAll('[data-user]').forEach(a => a.addEventListener('click', e => { e.preventDefault(); userDetail(Number(a.dataset.user)); }));
  }

  // ---------------------------------------------------------------- users

  const usersQuery = { q: '', groupId: '', status: '', sort: 'newest', page: 1 };

  async function pageUsers(el, alive) {
    await ensureGroups(true);
    if (!alive()) return;
    el.innerHTML = `
      <div class="toolbar">
        <input type="search" id="u-q" placeholder="搜索账号 / 昵称 / 邮箱 / ID" value="${esc(usersQuery.q)}">
        <select id="u-group">${groupOptions(usersQuery.groupId, true)}</select>
        <select id="u-status">
          <option value="">全部状态</option>
          <option value="active" ${usersQuery.status === 'active' ? 'selected' : ''}>正常</option>
          <option value="banned" ${usersQuery.status === 'banned' ? 'selected' : ''}>已封禁</option>
        </select>
        <select id="u-sort">
          ${[['newest', '最新注册'], ['oldest', '最早注册'], ['active', '最近活跃'], ['usage', '今日用量']]
            .map(([v, l]) => `<option value="${v}" ${usersQuery.sort === v ? 'selected' : ''}>${l}</option>`).join('')}
        </select>
        <span class="spacer"></span>
        <button class="btn-primary" id="u-new">新建用户</button>
      </div>
      <div id="u-batch"></div>
      <div class="table-wrap" id="u-table">${loading}</div>`;

    const selected = new Set();
    const tableEl = el.querySelector('#u-table');
    const batchEl = el.querySelector('#u-batch');

    const load = async () => {
      const params = new URLSearchParams({ ...usersQuery, size: 20 });
      const r = await api('GET', '/users?' + params);
      if (!alive()) return;
      selected.clear();
      renderBatch();
      tableEl.innerHTML = `
        <table><thead><tr>
          <th><input type="checkbox" id="u-all" aria-label="全选"></th>
          <th class="name-col">用户</th><th>分组</th><th>状态</th><th class="right">今日 AI</th><th class="right">设备</th><th>注册时间</th><th>最近活跃</th><th>操作</th>
        </tr></thead><tbody>
        ${r.items.length ? r.items.map(u => `<tr>
          <td><input type="checkbox" data-sel="${u.id}" aria-label="选择 ${esc(u.username)}"></td>
          <td><div class="primary">${esc(u.username)} <span class="muted small">#${u.id}</span></div>
              <div class="secondary">${esc(u.nickname || '未设置昵称')}${u.email ? ' · ' + esc(u.email) : ''}</div></td>
          <td><span class="badge blue">${esc(u.group_name)}</span>${u.group_expires_at ? `<div class="secondary">至 ${expiryToDate(u.group_expires_at)}</div>` : ''}
              ${u.daily_ai_requests != null || u.monthly_ai_tokens != null ? '<div class="secondary">自定义额度</div>' : ''}</td>
          <td>${u.status === 'banned' ? '<span class="badge red">已封禁</span>' : '<span class="badge green">正常</span>'}</td>
          <td class="right nowrap">对话 ${fmtNum(u.today_requests)} · 图像 ${fmtNum(u.today_images)} · 识别 ${fmtNum(u.today_ocr)}<div class="secondary">${fmtTokens(u.today_tokens)} tokens</div></td>
          <td class="right">${fmtNum(u.sessions)}</td>
          <td class="nowrap">${fmtDate(u.created_at)}</td>
          <td class="nowrap" title="${fmtTime(u.last_seen_at)}">${relTime(u.last_seen_at)}</td>
          <td class="nowrap"><button class="btn-link" data-detail="${u.id}">详情</button><button class="btn-link" data-edit="${u.id}">编辑</button></td>
        </tr>`).join('') : '<tr><td colspan="9" class="empty">没有符合条件的用户</td></tr>'}
        </tbody></table>${pager(r.total, r.page, r.size)}`;
    };

    function renderBatch() {
      if (!selected.size) { batchEl.innerHTML = ''; return; }
      batchEl.innerHTML = `<div class="batchbar">已选 ${selected.size} 个用户
        <button data-batch="group">调整分组</button><button data-batch="ban">封禁</button>
        <button data-batch="unban">解封</button><button class="btn-danger" data-batch="delete">删除</button>
        <button class="btn-link" data-batch="clear">取消选择</button></div>`;
    }

    const reload = () => load().catch(fail);
    el.querySelector('#u-q').addEventListener('input', debounce(e => { usersQuery.q = e.target.value.trim(); usersQuery.page = 1; reload(); }, 300));
    el.querySelector('#u-group').addEventListener('change', e => { usersQuery.groupId = e.target.value; usersQuery.page = 1; reload(); });
    el.querySelector('#u-status').addEventListener('change', e => { usersQuery.status = e.target.value; usersQuery.page = 1; reload(); });
    el.querySelector('#u-sort').addEventListener('change', e => { usersQuery.sort = e.target.value; usersQuery.page = 1; reload(); });
    el.querySelector('#u-new').addEventListener('click', () => createUserDialog(reload));

    tableEl.addEventListener('click', e => {
      const t = e.target;
      if (t.dataset.page) { usersQuery.page = Number(t.dataset.page); reload(); return; }
      if (t.dataset.detail) userDetail(Number(t.dataset.detail), reload);
      if (t.dataset.edit) editUserDialog(Number(t.dataset.edit), reload);
    });
    tableEl.addEventListener('change', e => {
      const t = e.target;
      if (t.id === 'u-all') {
        tableEl.querySelectorAll('[data-sel]').forEach(c => { c.checked = t.checked; t.checked ? selected.add(Number(c.dataset.sel)) : selected.delete(Number(c.dataset.sel)); });
      } else if (t.dataset.sel) {
        t.checked ? selected.add(Number(t.dataset.sel)) : selected.delete(Number(t.dataset.sel));
      }
      renderBatch();
    });
    batchEl.addEventListener('click', async e => {
      const action = e.target.dataset.batch;
      if (!action) return;
      const ids = [...selected];
      if (action === 'clear') { tableEl.querySelectorAll('input[type=checkbox]').forEach(c => { c.checked = false; }); selected.clear(); renderBatch(); return; }
      if (action === 'group') {
        openModal({
          title: `调整 ${ids.length} 个用户的分组`,
          body: `<form><label class="field"><span>目标分组</span><select name="groupId">${groupOptions(state.groups[0] && state.groups[0].id)}</select></label>
            <label class="field"><span>有效期至</span><input type="date" name="expires"><small>留空表示长期有效；到期后自动回到默认分组</small></label></form>`,
          onOk: async body => {
            const d = formData(body);
            const r = await api('POST', '/users/batch', { ids, action, groupId: d.groupId, groupExpiresAt: dateToExpiry(d.expires) });
            toast(`已调整 ${r.affected} 个用户`);
            reload();
          },
        });
        return;
      }
      const labels = { ban: ['封禁', '封禁后这些用户会立即被强制下线，且无法再登录。'], unban: ['解封', '解封后这些用户可以重新登录。'], delete: ['删除', '删除后账号、登录记录和用量记录都会被清除，无法恢复。'] };
      const [label, msg] = labels[action];
      if (!(await confirmBox(`${label} ${ids.length} 个用户`, msg, label, action !== 'unban'))) return;
      try {
        const r = await api('POST', '/users/batch', { ids, action });
        toast(`已${label} ${r.affected} 个用户`);
        reload();
      } catch (err) { fail(err); }
    });

    await load();
  }

  function createUserDialog(onDone) {
    const pw = randomPassword();
    openModal({
      title: '新建用户',
      body: `<form>
        <div class="grid-2">
          <label class="field"><span>账号</span><input name="username" required autocomplete="off" placeholder="3–32 位字母、数字或 _ . @ -"></label>
          <label class="field"><span>初始密码</span><div class="row"><input name="password" value="${pw}" autocomplete="off"><button type="button" data-gen>换一个</button></div></label>
          <label class="field"><span>分组</span><select name="groupId">${groupOptions((state.groups.find(g => g.is_default) || {}).id)}</select></label>
          <label class="field"><span>有效期至</span><input type="date" name="expires"><small>留空表示长期有效</small></label>
          <label class="field"><span>昵称</span><input name="nickname" maxlength="20"></label>
          <label class="field"><span>邮箱</span><input name="email" type="email"></label>
        </div>
        <label class="field"><span>备注（仅管理员可见）</span><input name="note" maxlength="500"></label></form>`,
      okText: '创建',
      onOk: async body => {
        const d = formData(body);
        await api('POST', '/users', { ...d, groupExpiresAt: dateToExpiry(d.expires) });
        showSecret('用户已创建', `账号 ${d.username} 的初始密码：`, d.password);
        onDone && onDone();
      },
    }).body.querySelector('[data-gen]').addEventListener('click', e => {
      e.target.closest('.row').querySelector('input').value = randomPassword();
    });
  }

  function randomPassword() {
    const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789';
    const buf = new Uint32Array(10);
    crypto.getRandomValues(buf);
    const s = Array.from(buf, n => chars[n % chars.length]).join('');
    return /\d/.test(s) && /[a-z]/i.test(s) ? s : randomPassword();
  }

  async function userDetail(id, onChange) {
    const d = await api('GET', `/users/${id}`).catch(fail);
    if (!d) return;
    await ensureGroups();
    const u = d.user;
    const q = d.quota;
    const m = openModal({
      title: `用户详情 · ${u.username}`,
      wide: true,
      noFooter: true,
      body: `
        <div class="detail-grid">
          <div>
            <h4>基本信息</h4>
            <dl class="kv">
              <dt>ID</dt><dd>${u.id}</dd>
              <dt>账号</dt><dd>${esc(u.username)}</dd>
              <dt>昵称</dt><dd>${esc(u.nickname) || '—'}</dd>
              <dt>邮箱</dt><dd>${esc(u.email) || '—'}</dd>
              <dt>分组</dt><dd><span class="badge blue">${esc(u.group_name)}</span> ${u.group_expires_at ? `有效期至 ${expiryToDate(u.group_expires_at)}` : '长期'}</dd>
              <dt>状态</dt><dd>${u.status === 'banned' ? `<span class="badge red">已封禁</span> ${esc(u.ban_reason || '')}` : '<span class="badge green">正常</span>'}</dd>
              <dt>注册</dt><dd>${fmtTime(u.created_at)} · ${esc(u.register_ip || '—')}</dd>
              <dt>最近登录</dt><dd>${fmtTime(u.last_login_at)} · ${esc(u.last_login_ip || '—')}</dd>
              <dt>最近活跃</dt><dd>${fmtTime(u.last_seen_at)}</dd>
              <dt>备注</dt><dd>${esc(u.note) || '—'}</dd>
            </dl>
          </div>
          <div>
            <h4>额度${u.daily_ai_requests != null || u.monthly_ai_tokens != null ? ' <span class="badge orange">已自定义</span>' : ''}</h4>
            <div class="quota-item"><div class="row"><span>今日 AI 对话次数</span><span>${fmtNum(q.dailyUsed)} / ${limitText(q.dailyRequests)}</span></div>${quotaBar(q.dailyUsed, q.dailyRequests)}</div>
            <div class="quota-item"><div class="row"><span>本月 Tokens</span><span>${fmtTokens(q.monthlyUsed)} / ${fmtTokens(q.monthlyTokens)}</span></div>${quotaBar(q.monthlyUsed, q.monthlyTokens)}</div>
            <div class="quota-item"><div class="row"><span>今日 AI 图像处理</span><span>${fmtNum(q.imagesUsed)} / ${limitText(q.dailyImages)}</span></div>${quotaBar(q.imagesUsed, q.dailyImages)}</div>
            <div class="quota-item"><div class="row"><span>今日 AI 识别页数</span><span>${fmtNum(q.ocrUsed)} / ${limitText(q.dailyOcrPages)}</span></div>${quotaBar(q.ocrUsed, q.dailyOcrPages)}</div>
            <dl class="kv">
              <dt>单次字数</dt><dd>${limitText(q.maxInputChars)}</dd>
              <dt>设备上限</dt><dd>${fmtNum(q.maxDevices)} 台</dd>
              <dt>开通功能</dt><dd><div class="tags">${q.features.map(f => `<span class="badge blue">${esc(featureLabel(f))}</span>`).join('') || '—'}</div></dd>
            </dl>
          </div>
        </div>
        <h4>登录设备（${d.sessions.length}）</h4>
        ${d.sessions.length ? `<div class="table-wrap"><table><thead><tr><th>设备</th><th>App 版本</th><th>IP</th><th>登录时间</th><th>最近活跃</th><th></th></tr></thead><tbody>
          ${d.sessions.map(s => `<tr><td>${esc(s.device) || '—'}</td><td>${esc(s.app_version) || '—'}</td><td class="mono">${esc(s.ip)}</td>
            <td class="nowrap">${fmtTime(s.created_at)}</td><td class="nowrap">${relTime(s.last_seen_at)}</td>
            <td><button class="btn-link danger" data-kick="${esc(s.id)}">下线</button></td></tr>`).join('')}
        </tbody></table></div>` : '<p class="muted">当前没有已登录的设备</p>'}
        <h4>近 30 天用量</h4>
        ${d.usage.length ? `<div class="table-wrap"><table><thead><tr><th>日期</th><th class="right">AI 对话</th><th class="right">Tokens</th><th class="right">图像处理</th><th class="right">识别页数</th></tr></thead><tbody>
          ${d.usage.map(x => `<tr><td>${esc(x.day)}</td><td class="right">${fmtNum(x.ai_requests)}</td><td class="right">${fmtNum(x.ai_tokens)}</td><td class="right">${fmtNum(x.image_requests)}</td><td class="right">${fmtNum(x.ocr_pages)}</td></tr>`).join('')}
        </tbody></table></div>` : '<p class="muted">暂无用量</p>'}
        <h4>最近 AI 调用</h4>
        ${d.logs.length ? `<div class="table-wrap"><table><thead><tr><th>时间</th><th>功能</th><th class="right">Tokens</th><th class="right">耗时</th><th>结果</th></tr></thead><tbody>
          ${d.logs.map(l => `<tr><td class="nowrap">${fmtTime(l.created_at)}</td><td>${esc(featureLabel(l.feature))}</td>
            <td class="right">${fmtNum(l.prompt_tokens + l.completion_tokens)}</td><td class="right">${(l.latency_ms / 1000).toFixed(1)}s</td>
            <td>${statusBadge(l.status, l.error)}</td></tr>`).join('')}
        </tbody></table></div>` : '<p class="muted">暂无调用</p>'}
        <div class="action-row">
          <button class="btn-primary" data-a="edit">编辑资料与额度</button>
          <button data-a="reset">重置密码</button>
          <button data-a="kickall" ${d.sessions.length ? '' : 'disabled'}>全部下线</button>
          ${u.status === 'banned' ? '<button data-a="unban">解封</button>' : '<button class="btn-danger" data-a="ban">封禁</button>'}
          <span class="spacer"></span>
          <button class="btn-danger" data-a="delete">删除账号</button>
        </div>`,
    });
    const refresh = () => { m.close(); onChange && onChange(); userDetail(id, onChange); };
    m.body.addEventListener('click', async e => {
      const t = e.target;
      try {
        if (t.dataset.kick) {
          await api('POST', `/users/${id}/kick`, { sessionId: t.dataset.kick });
          toast('已下线该设备');
          refresh();
          return;
        }
        switch (t.dataset.a) {
          case 'edit': m.close(); editUserDialog(id, onChange); break;
          case 'reset':
            if (await confirmBox('重置密码', `将为 ${esc(u.username)} 生成新的随机密码，并让其所有设备退出登录。`, '重置')) {
              const r = await api('POST', `/users/${id}/reset-password`);
              showSecret('密码已重置', `${u.username} 的新密码：`, r.password);
            }
            break;
          case 'kickall':
            if (await confirmBox('全部下线', `让 ${esc(u.username)} 在所有设备上退出登录？`, '下线')) {
              await api('POST', `/users/${id}/kick`, {});
              toast('已全部下线');
              refresh();
            }
            break;
          case 'ban': m.close(); banDialog(u, onChange); break;
          case 'unban':
            await api('PATCH', `/users/${id}`, { status: 'active', banReason: '' });
            toast('已解封');
            refresh();
            break;
          case 'delete':
            if (await confirmBox('删除账号', `确定删除 ${esc(u.username)}？账号、登录记录和用量记录都会被清除，无法恢复。`, '删除')) {
              await api('DELETE', `/users/${id}`);
              toast('已删除');
              m.close();
              onChange && onChange();
            }
            break;
          default:
        }
      } catch (err) { fail(err); }
    });
  }

  function banDialog(u, onDone) {
    openModal({
      title: `封禁 ${u.username}`,
      body: `<form><label class="field"><span>封禁原因（会显示给用户）</span><input name="banReason" maxlength="100" placeholder="例如：违反用户协议，批量注册"></label>
        <p class="muted small">封禁后该用户会立即被强制下线，且无法再登录。</p></form>`,
      okText: '封禁',
      danger: true,
      onOk: async body => {
        await api('PATCH', `/users/${u.id}`, { status: 'banned', banReason: formData(body).banReason });
        toast('已封禁');
        onDone && onDone();
      },
    });
  }

  async function editUserDialog(id, onDone) {
    const d = await api('GET', `/users/${id}`).catch(fail);
    if (!d) return;
    await ensureGroups();
    const u = d.user;
    const m = openModal({
      title: `编辑用户 · ${u.username}`,
      body: `<form>
        <div class="grid-2">
          <label class="field"><span>昵称</span><input name="nickname" value="${esc(u.nickname)}" maxlength="20"></label>
          <label class="field"><span>邮箱</span><input name="email" type="email" value="${esc(u.email)}"></label>
          <label class="field"><span>分组</span><select name="groupId">${groupOptions(u.group_id)}</select></label>
          <label class="field"><span>有效期至</span><input type="date" name="expires" value="${expiryToDate(u.group_expires_at)}">
            <small><button type="button" class="btn-link" data-ext="1">+1 个月</button><button type="button" class="btn-link" data-ext="3">+3 个月</button><button type="button" class="btn-link" data-ext="12">+1 年</button><button type="button" class="btn-link" data-ext="0">长期</button></small></label>
          <label class="field"><span>每日 AI 次数（自定义）</span><input name="dailyAiRequests" type="number" min="-1" value="${u.daily_ai_requests ?? ''}" placeholder="跟随分组"><small>留空跟随分组，-1 为不限</small></label>
          <label class="field"><span>每月 Tokens（自定义）</span><input name="monthlyAiTokens" type="number" min="-1" value="${u.monthly_ai_tokens ?? ''}" placeholder="跟随分组"><small>留空跟随分组，-1 为不限</small></label>
          <label class="field"><span>状态</span><select name="status"><option value="active">正常</option><option value="banned" ${u.status === 'banned' ? 'selected' : ''}>封禁</option></select></label>
          <label class="field"><span>封禁原因</span><input name="banReason" value="${esc(u.ban_reason)}" maxlength="100"></label>
        </div>
        <label class="field"><span>备注（仅管理员可见）</span><input name="note" value="${esc(u.note)}" maxlength="500"></label>
        <p class="muted small">到期后自动回到默认分组，并清除自定义额度。</p></form>`,
      onOk: async body => {
        const f = formData(body);
        await api('PATCH', `/users/${id}`, {
          nickname: f.nickname, email: f.email, groupId: f.groupId, groupExpiresAt: dateToExpiry(f.expires),
          dailyAiRequests: f.dailyAiRequests, monthlyAiTokens: f.monthlyAiTokens, status: f.status, banReason: f.banReason, note: f.note,
        });
        toast('已保存');
        onDone && onDone();
      },
    });
    m.body.addEventListener('click', e => {
      const months = e.target.dataset.ext;
      if (months === undefined) return;
      const input = m.body.querySelector('[name=expires]');
      if (months === '0') { input.value = ''; return; }
      const base = input.value ? new Date(input.value + 'T00:00:00') : new Date();
      base.setMonth(base.getMonth() + Number(months));
      input.value = `${base.getFullYear()}-${pad(base.getMonth() + 1)}-${pad(base.getDate())}`;
    });
  }

  const statusBadge = (s, err) => ({
    ok: '<span class="badge green">成功</span>',
    error: `<span class="badge red" title="${esc(err)}">失败</span>`,
    rejected: `<span class="badge orange" title="${esc(err)}">拦截</span>`,
  }[s] || esc(s));

  // ---------------------------------------------------------------- groups

  async function pageGroups(el, alive) {
    const groups = await ensureGroups(true);
    if (!alive()) return;
    el.innerHTML = `
      <div class="alert info">每个用户属于一个分组，分组决定 AI 功能的额度：对话类（总结、问答、翻译、拍题）按次数和 Tokens，图像类（证件照、去手写、去水印、消除、修复、变清晰）按次数，文字识别（提取文字、转 Word/Excel）按页数。-1 表示不限。修改后立即对该分组所有用户生效；单个用户还可以在「用户管理」里设置自定义额度。</div>
      <div class="toolbar"><span class="spacer"></span><button class="btn-primary" id="g-new">新建分组</button></div>
      <div class="table-wrap"><table><thead><tr>
        <th class="name-col">分组</th><th class="right">用户数</th><th class="right">每日 AI 对话</th><th class="right">每月 Tokens</th><th class="right">每日图像处理</th><th class="right">每日识别页数</th><th class="right">单次字数</th><th class="right">设备数</th><th>开通功能</th><th>操作</th>
      </tr></thead><tbody>
      ${groups.map(g => `<tr>
        <td><div class="primary">${esc(g.name)} ${g.is_default ? '<span class="badge green">默认</span>' : ''}</div><div class="secondary">${esc(g.description)}</div></td>
        <td class="right">${fmtNum(g.users)}</td>
        <td class="right">${limitText(g.daily_ai_requests)}</td>
        <td class="right">${fmtTokens(g.monthly_ai_tokens)}</td>
        <td class="right">${limitText(g.daily_image_requests)}</td>
        <td class="right">${limitText(g.daily_ocr_pages)}</td>
        <td class="right">${limitText(g.max_input_chars)}</td>
        <td class="right">${fmtNum(g.max_devices)}</td>
        <td><div class="tags">${g.features.split(',').filter(Boolean).map(f => `<span class="badge blue">${esc(featureLabel(f))}</span>`).join('') || '<span class="muted">无</span>'}</div></td>
        <td class="nowrap"><button class="btn-link" data-edit="${g.id}">编辑</button>
          ${g.is_default ? '' : `<button class="btn-link" data-default="${g.id}">设为默认</button><button class="btn-link danger" data-del="${g.id}">删除</button>`}</td>
      </tr>`).join('')}
      </tbody></table></div>`;
    const reload = () => renderPage();
    el.querySelector('#g-new').addEventListener('click', () => groupDialog(null, reload));
    el.querySelector('tbody').addEventListener('click', async e => {
      const t = e.target;
      const g = groups.find(x => String(x.id) === (t.dataset.edit || t.dataset.default || t.dataset.del));
      if (!g) return;
      try {
        if (t.dataset.edit) groupDialog(g, reload);
        if (t.dataset.default && await confirmBox('设为默认分组', `新注册的用户将进入「${esc(g.name)}」，会员到期的用户也会回到这个分组。`, '确定', false)) {
          await api('POST', `/groups/${g.id}/default`);
          toast('已设为默认分组');
          reload();
        }
        if (t.dataset.del && await confirmBox('删除分组', `确定删除「${esc(g.name)}」？`, '删除')) {
          await api('DELETE', `/groups/${g.id}`);
          toast('已删除');
          reload();
        }
      } catch (err) { fail(err); }
    });
  }

  function groupDialog(g, onDone) {
    const v = g || { name: '', description: '', daily_ai_requests: 20, monthly_ai_tokens: 200000, daily_image_requests: 5, daily_ocr_pages: 50, max_input_chars: 6000, max_devices: 2, features: state.features.map(f => f.key).join(','), sort: state.groups.length };
    const feats = v.features.split(',');
    openModal({
      title: g ? `编辑分组 · ${g.name}` : '新建分组',
      body: `<form>
        <div class="grid-2">
          <label class="field"><span>名称</span><input name="name" value="${esc(v.name)}" maxlength="20" required></label>
          <label class="field"><span>排序</span><input name="sort" type="number" value="${v.sort}"></label>
        </div>
        <label class="field"><span>说明</span><input name="description" value="${esc(v.description)}" maxlength="100"></label>
        <div class="grid-2">
          <label class="field"><span>每日 AI 对话次数</span><input name="daily_ai_requests" type="number" min="-1" value="${v.daily_ai_requests}" required><small>总结、问答、翻译、拍题；每天 0 点重置，-1 不限</small></label>
          <label class="field"><span>每月 AI Tokens</span><input name="monthly_ai_tokens" type="number" min="-1" value="${v.monthly_ai_tokens}" required><small>每月 1 日重置，-1 不限；一次文档总结约 2000–8000</small></label>
          <label class="field"><span>每日 AI 图像处理次数</span><input name="daily_image_requests" type="number" min="-1" value="${v.daily_image_requests ?? 5}" required><small>证件照、修复、消除、变清晰每次约 0.14–0.2 元</small></label>
          <label class="field"><span>每日 AI 识别页数</span><input name="daily_ocr_pages" type="number" min="-1" value="${v.daily_ocr_pages ?? 50}" required><small>提取文字、转 Word/Excel，每页约 0.2 分</small></label>
          <label class="field"><span>单次输入字数上限</span><input name="max_input_chars" type="number" min="100" max="120000" value="${v.max_input_chars}" required></label>
          <label class="field"><span>同时登录设备数</span><input name="max_devices" type="number" min="1" max="50" value="${v.max_devices}" required><small>超出时最早登录的设备自动退出</small></label>
        </div>
        <div class="field"><span class="muted small">开通的 AI 功能</span><div>
          ${state.features.map(f => `<label class="check"><input type="checkbox" name="features" data-multi="1" value="${f.key}" ${feats.includes(f.key) ? 'checked' : ''}>${esc(f.label)}</label>`).join('')}
        </div></div></form>`,
      onOk: async body => {
        const d = formData(body);
        if (g) await api('PATCH', `/groups/${g.id}`, d);
        else await api('POST', '/groups', d);
        toast('已保存');
        onDone();
      },
    });
  }

  // ---------------------------------------------------------------- AI logs

  const aiQuery = { username: '', feature: '', status: '', page: 1 };

  async function pageAiLogs(el, alive) {
    el.innerHTML = `
      <div class="toolbar">
        <input type="search" id="a-user" placeholder="按账号筛选（精确）" value="${esc(aiQuery.username)}">
        <select id="a-feature"><option value="">全部功能</option>${state.features.map(f => `<option value="${f.key}" ${aiQuery.feature === f.key ? 'selected' : ''}>${esc(f.label)}</option>`).join('')}</select>
        <select id="a-status">${[['', '全部结果'], ['ok', '成功'], ['error', '失败'], ['rejected', '超额/拦截']].map(([v, l]) => `<option value="${v}" ${aiQuery.status === v ? 'selected' : ''}>${l}</option>`).join('')}</select>
      </div>
      <div class="alert info">出于隐私保护，这里只记录调用元数据（功能、字数、Tokens、耗时、结果），不保存用户提交的文字和图片。记录保留 180 天。</div>
      <div class="table-wrap" id="a-table">${loading}</div>`;
    const tableEl = el.querySelector('#a-table');
    const load = async () => {
      const r = await api('GET', '/ai-logs?' + new URLSearchParams({ ...aiQuery, size: 30 }));
      if (!alive()) return;
      tableEl.innerHTML = `<table><thead><tr><th>时间</th><th>用户</th><th>功能</th><th>模型</th><th class="right">输入字数</th><th class="right">Tokens（入/出）</th><th class="right">耗时</th><th>结果</th></tr></thead><tbody>
        ${r.items.length ? r.items.map(l => `<tr>
          <td class="nowrap">${fmtTime(l.created_at)}</td>
          <td>${l.user_id ? `<a href="#/ai" data-user="${l.user_id}">${esc(l.username || '#' + l.user_id)}</a>` : '<span class="muted">已注销</span>'}</td>
          <td>${esc(featureLabel(l.feature))}</td><td class="mono">${esc(l.model) || '—'}</td>
          <td class="right">${fmtNum(l.input_chars)}</td>
          <td class="right nowrap">${fmtNum(l.prompt_tokens)} / ${fmtNum(l.completion_tokens)}</td>
          <td class="right">${l.latency_ms ? (l.latency_ms / 1000).toFixed(1) + 's' : '—'}</td>
          <td>${statusBadge(l.status, l.error)}${l.error ? `<div class="secondary">${esc(l.error.slice(0, 60))}</div>` : ''}</td>
        </tr>`).join('') : '<tr><td colspan="8" class="empty">暂无记录</td></tr>'}
        </tbody></table>${pager(r.total, r.page, r.size)}`;
    };
    const reload = () => load().catch(fail);
    el.querySelector('#a-user').addEventListener('input', debounce(e => { aiQuery.username = e.target.value.trim(); aiQuery.page = 1; reload(); }, 400));
    el.querySelector('#a-feature').addEventListener('change', e => { aiQuery.feature = e.target.value; aiQuery.page = 1; reload(); });
    el.querySelector('#a-status').addEventListener('change', e => { aiQuery.status = e.target.value; aiQuery.page = 1; reload(); });
    tableEl.addEventListener('click', e => {
      if (e.target.dataset.page) { aiQuery.page = Number(e.target.dataset.page); reload(); }
      if (e.target.dataset.user) { e.preventDefault(); userDetail(Number(e.target.dataset.user)); }
    });
    await load();
  }

  // ---------------------------------------------------------------- feedback

  const fbQuery = { status: 'open', page: 1 };

  async function pageFeedback(el, alive) {
    el.innerHTML = `
      <div class="toolbar">${[['open', '待处理'], ['done', '已处理'], ['', '全部']].map(([v, l]) => `<button data-status="${v}" class="${fbQuery.status === v ? 'btn-primary' : ''}">${l}</button>`).join('')}</div>
      <div class="table-wrap" id="f-list">${loading}</div>`;
    const listEl = el.querySelector('#f-list');
    const load = async () => {
      const r = await api('GET', '/feedback?' + new URLSearchParams({ ...fbQuery, size: 20 }));
      if (!alive()) return;
      listEl.innerHTML = (r.items.length ? r.items.map(f => `
        <div class="fb-item">
          <div class="row" style="justify-content:space-between">
            <div>${f.user_id ? `<a href="#/feedback" data-user="${f.user_id}">${esc(f.username)}</a>` : `<span class="muted">${esc(f.username || '已注销用户')}</span>`}
              ${f.status === 'open' ? '<span class="badge orange">待处理</span>' : '<span class="badge green">已处理</span>'}</div>
            <div class="row">
              <button class="btn-link" data-toggle="${f.id}" data-to="${f.status === 'open' ? 'done' : 'open'}">${f.status === 'open' ? '标记已处理' : '重新打开'}</button>
              <button class="btn-link" data-note="${f.id}">备注</button>
              <button class="btn-link danger" data-del="${f.id}">删除</button>
            </div>
          </div>
          <div class="content">${esc(f.content)}</div>
          <div class="meta"><span>${fmtTime(f.created_at)}</span>${f.contact ? `<span>联系方式：${esc(f.contact)}</span>` : ''}
            ${f.app_version ? `<span>版本 ${esc(f.app_version)}</span>` : ''}${f.device ? `<span>${esc(f.device)}</span>` : ''}</div>
          ${f.admin_note ? `<div class="note">备注：${esc(f.admin_note)}</div>` : ''}
        </div>`).join('') : '<div class="empty">暂无反馈</div>') + pager(r.total, r.page, r.size);
      listEl._items = r.items;
    };
    const reload = () => load().catch(fail);
    el.querySelector('.toolbar').addEventListener('click', e => {
      if (e.target.dataset.status === undefined) return;
      fbQuery.status = e.target.dataset.status;
      fbQuery.page = 1;
      renderPage();
    });
    listEl.addEventListener('click', async e => {
      const t = e.target;
      try {
        if (t.dataset.page) { fbQuery.page = Number(t.dataset.page); reload(); }
        if (t.dataset.user) { e.preventDefault(); userDetail(Number(t.dataset.user)); }
        if (t.dataset.toggle) {
          await api('PATCH', `/feedback/${t.dataset.toggle}`, { status: t.dataset.to });
          state.openFeedback = Math.max(0, state.openFeedback + (t.dataset.to === 'done' ? -1 : 1));
          renderNav();
          reload();
        }
        if (t.dataset.note) {
          const f = listEl._items.find(x => String(x.id) === t.dataset.note);
          openModal({
            title: '处理备注',
            body: `<form><label class="field"><span>备注（仅管理员可见）</span><textarea name="adminNote" maxlength="500">${esc(f.admin_note)}</textarea></label></form>`,
            onOk: async body => { await api('PATCH', `/feedback/${f.id}`, formData(body)); reload(); },
          });
        }
        if (t.dataset.del && await confirmBox('删除反馈', '确定删除这条反馈？', '删除')) {
          await api('DELETE', `/feedback/${t.dataset.del}`);
          reload();
        }
      } catch (err) { fail(err); }
    });
    await load();
  }

  // ---------------------------------------------------------------- settings

  const AI_PRESETS = [
    { name: '阿里云百炼（通义千问）', url: 'https://dashscope.aliyuncs.com/compatible-mode/v1', text: 'qwen-plus', vision: 'qwen-vl-plus' },
    { name: '火山方舟（豆包）', url: 'https://ark.cn-beijing.volces.com/api/v3', text: '', vision: '', hint: '模型名填写方舟控制台里的模型 ID 或推理接入点 ID' },
    { name: 'DeepSeek（仅文本）', url: 'https://api.deepseek.com/v1', text: 'deepseek-chat', vision: '', hint: 'DeepSeek 不支持图片，拍题解答需要另配视觉模型' },
    { name: '智谱 AI（GLM）', url: 'https://open.bigmodel.cn/api/paas/v4', text: '', vision: '', hint: '模型名以智谱开放平台文档为准' },
  ];

  async function pageSettings(el, alive) {
    const r = await api('GET', '/settings');
    if (!alive()) return;
    const v = r.values;
    const ta = (name, label, hint, cls = '') => `<label class="field"><span>${label}</span><textarea name="${name}" class="${cls}">${esc(v[name])}</textarea>${hint ? `<small>${hint}</small>` : ''}</label>`;
    const inp = (name, label, hint, attrs = '') => `<label class="field"><span>${label}</span><input name="${name}" value="${esc(v[name])}" ${attrs}>${hint ? `<small>${hint}</small>` : ''}</label>`;
    el.innerHTML = `
      <form class="card" data-section="ai">
        <h3>AI 服务 ${r.hasApiKey ? '<span class="badge green">已配置</span>' : '<span class="badge orange">未配置</span>'}</h3>
        <label class="field"><span>快速填写</span><select id="preset"><option value="">选择服务商自动填入接口地址…</option>${AI_PRESETS.map((p, i) => `<option value="${i}">${esc(p.name)}</option>`).join('')}</select><small id="preset-hint">支持任何兼容 OpenAI Chat Completions 格式的接口</small></label>
        <div class="grid-2">
          ${inp('ai_base_url', '接口地址（Base URL）', '会请求 {接口地址}/chat/completions')}
          <label class="field"><span>API Key</span><input name="ai_api_key" type="password" autocomplete="new-password" placeholder="${r.hasApiKey ? esc(v.ai_api_key) + '（留空则不修改）' : '粘贴服务商控制台创建的 API Key'}">
            <small>只保存在服务器上，App 端拿不到。${r.hasApiKey ? '<label class="check"><input type="checkbox" name="ai_api_key_clear">清除已保存的 Key</label>' : ''}</small></label>
          ${inp('ai_text_model', '文本模型', '用于总结、问答、纠错、翻译')}
          ${inp('ai_vision_model', '视觉模型', '用于拍题解答（需支持图片输入），推荐 qwen3-vl-plus')}
          ${inp('ai_max_output_tokens', '单次最大输出 Tokens', '100–32000', 'type="number"')}
          ${inp('ai_timeout_seconds', '超时（秒）', '10–300', 'type="number"')}
        </div>
        ${ta('ai_extra_body', '附加请求参数（JSON，可选）', '会合并到每次请求里，例如 {"enable_thinking": false} 关闭千问 3 的思考模式以加快响应')}
        <div class="row" id="ai-test-result"></div>
        <div class="card-foot"><button type="button" data-test="text">测试文本模型</button><button type="button" data-test="vision">测试视觉模型</button><button class="btn-primary">保存</button></div>
      </form>

      <form class="card" data-section="vision">
        <h3>图像与文字识别（阿里云百炼） ${r.hasApiKey || r.hasDashscopeKey ? '<span class="badge green">可用</span>' : '<span class="badge orange">未配置</span>'}</h3>
        <div class="alert info">这些功能直接调用百炼的原生接口，默认使用上面「AI 服务」里的同一个 API Key（需为北京地域的百炼 Key）。文档类的去手写、去水印只让模型<b>定位</b>笔迹和水印，由 App 在原图上擦除，印刷文字不会被改动。</div>
        <div class="grid-2">
          ${inp('dashscope_base_url', '百炼原生接口地址', '一般不用改')}
          <label class="field"><span>百炼 API Key（可选）</span><input name="dashscope_api_key" type="password" autocomplete="new-password" placeholder="${r.hasDashscopeKey ? esc(v.dashscope_api_key) + '（留空则不修改）' : '留空则使用上面的 API Key'}">
            <small>${r.hasDashscopeKey ? '<label class="check"><input type="checkbox" name="dashscope_api_key_clear">清除，改用上面的 Key</label>' : '上面的 AI 服务换成其他厂商时，在这里单独填写百炼的 Key'}</small></label>
          ${inp('ocr_model', '文字识别模型', '提取文字、转 Word/Excel；qwen3.5-ocr 每页约 0.2 分')}
          ${inp('detect_model', '笔迹/水印定位模型', '去手写、试卷还原、去水印；qwen3-vl-plus')}
          ${inp('idphoto_model', '证件照模型', 'AI 抠图、换装；qwen-image-3.0-pro 约 0.19 元/张（构图不变）')}
          ${inp('edit_model', '智能消除模型', '涂抹消除物体；qwen-image-3.0 约 0.2 元/张')}
          ${inp('restore_model', '老照片修复模型', '去划痕、上色；qwen-image-3.0 约 0.2 元/张')}
          ${inp('upscale_model', '变清晰模型', '图片变清晰；wanx2.1-imageedit 约 0.14 元/张')}
        </div>
        <label class="switch"><input type="checkbox" name="image_ai_auto" ${v.image_ai_auto === 'true' ? 'checked' : ''}>证件照、老照片修复、图片变清晰打开时自动使用 AI</label>
        <div class="muted small" style="margin: 2px 0 14px">关闭（推荐）：先显示免费的本地处理结果，用户点「AI」才调用模型，避免每次打开都产生费用。</div>
        ${ta('model_prices', '模型单价（元，用于概览页的费用估算）', 'JSON：perCall 为每次成功调用的价格，input / output 为每百万 Tokens 的价格。以百炼控制台的价格为准')}
        <div class="row" id="vision-test-result"></div>
        <div class="card-foot"><button type="button" data-check-models>检查模型是否可用</button><button type="button" data-test-vision>测试文字识别</button><button class="btn-primary">保存</button></div>
      </form>

      <form class="card" data-section="register">
        <h3>注册与登录</h3>
        <label class="switch"><input type="checkbox" name="registration_open" ${v.registration_open === 'true' ? 'checked' : ''}>开放新用户注册</label>
        ${inp('invite_code', '邀请码', '填写后注册时必须输入此邀请码，留空表示不需要', 'maxlength="32" autocomplete="off"')}
        <div class="card-foot"><button class="btn-primary">保存</button></div>
      </form>

      <form class="card" data-section="app">
        <h3>App 公告与版本</h3>
        ${ta('announcement', '公告', '用户打开 App 时弹出一次（内容变化后会再次弹出），留空表示没有公告')}
        <div class="grid-2">
          ${inp('latest_version_code', '最新版本号（versionCode）', '大于用户当前版本时提示更新', 'type="number" min="0"')}
          ${inp('min_version_code', '最低支持版本号', '低于此版本的 App 必须更新后才能使用', 'type="number" min="0"')}
        </div>
        ${inp('update_url', '下载地址', 'APK 下载链接或应用商店页面')}
        ${ta('update_note', '更新说明', '')}
        <div class="card-foot"><button class="btn-primary">保存</button></div>
      </form>

      <form class="card" data-section="policy">
        <h3>协议 <span class="small"><a href="/privacy" target="_blank" rel="noopener">查看隐私政策页面</a> · <a href="/terms" target="_blank" rel="noopener">查看用户协议页面</a></span></h3>
        <div class="alert warn">请把协议中的【运营主体名称】【联系邮箱】替换为真实信息，并建议请法务审核后再上架应用商店。App 内展示和公开页面都使用这里的内容。</div>
        ${ta('privacy_policy', '隐私政策', '', 'tall')}
        ${ta('user_terms', '用户协议', '', 'tall')}
        <div class="card-foot"><button class="btn-primary">保存</button></div>
      </form>`;

    el.querySelectorAll('form[data-section]').forEach(form => form.addEventListener('submit', async e => {
      e.preventDefault();
      const d = formData(form);
      if ('registration_open' in d) d.registration_open = String(d.registration_open);
      if ('image_ai_auto' in d) d.image_ai_auto = String(d.image_ai_auto);
      if ('ai_api_key_clear' in d) d.ai_api_key_clear = String(d.ai_api_key_clear);
      if (d.ai_api_key === '') delete d.ai_api_key;
      if ('dashscope_api_key_clear' in d) d.dashscope_api_key_clear = String(d.dashscope_api_key_clear);
      if (d.dashscope_api_key === '') delete d.dashscope_api_key;
      const btn = form.querySelector('.btn-primary');
      btn.disabled = true;
      try {
        await api('PUT', '/settings', d);
        toast('已保存');
        if (form.dataset.section === 'ai' || form.dataset.section === 'vision') renderPage();
      } catch (err) { fail(err); } finally { btn.disabled = false; }
    }));

    el.querySelector('#preset').addEventListener('change', e => {
      const p = AI_PRESETS[e.target.value];
      if (!p) return;
      const f = el.querySelector('form[data-section=ai]');
      f.ai_base_url.value = p.url;
      if (p.text) f.ai_text_model.value = p.text;
      if (p.vision) f.ai_vision_model.value = p.vision;
      el.querySelector('#preset-hint').textContent = p.hint || '已填入接口地址和推荐模型，填写 API Key 后保存';
    });

    el.querySelector('[data-check-models]').addEventListener('click', async e => {
      const b = e.target;
      const out = el.querySelector('#vision-test-result');
      out.innerHTML = '<span class="muted">正在查询百炼的模型列表（不产生费用）…</span>';
      b.disabled = true;
      try {
        const list = await api('POST', '/settings/check-models', {});
        out.innerHTML = list.map(m => `<div>${m.available === true ? '<span class="badge green">可用</span>' : m.available === false ? '<span class="badge red">不可用</span>' : '<span class="badge orange">未知</span>'} ${esc(m.label)}：<code>${esc(m.model)}</code></div>`).join('')
          + (list.some(m => m.available === null) ? '<div class="muted small">读取模型列表失败，请检查 API Key 和接口地址</div>' : '');
      } catch (err) {
        out.innerHTML = `<span class="badge red">失败</span> <span>${esc(err.message)}</span>`;
      } finally { b.disabled = false; }
    });

    el.querySelector('[data-test-vision]').addEventListener('click', async e => {
      const b = e.target;
      const out = el.querySelector('#vision-test-result');
      out.innerHTML = '<span class="muted">正在识别一张测试图片（使用已保存的设置）…</span>';
      b.disabled = true;
      try {
        const t = await api('POST', '/settings/test-vision', {});
        out.innerHTML = t.ok
          ? `<span class="badge green">成功</span> <span>识别结果「${esc(t.reply.replace(/\n/g, ' / '))}」，耗时 ${(t.latencyMs / 1000).toFixed(1)}s</span>`
          : `<span class="badge red">失败</span> <span>${esc(t.error)}</span>`;
      } catch (err) {
        out.innerHTML = `<span class="badge red">失败</span> <span>${esc(err.message)}</span>`;
      } finally { b.disabled = false; }
    });

    el.querySelectorAll('[data-test]').forEach(b => b.addEventListener('click', async () => {
      const out = el.querySelector('#ai-test-result');
      out.innerHTML = '<span class="muted">正在测试（使用已保存的设置）…</span>';
      b.disabled = true;
      try {
        const t = await api('POST', '/settings/test-ai', { vision: b.dataset.test === 'vision' });
        out.innerHTML = t.ok
          ? `<span class="badge green">成功</span> <span>${esc(t.model)} 回复「${esc(t.reply)}」，耗时 ${(t.latencyMs / 1000).toFixed(1)}s</span>`
          : `<span class="badge red">失败</span> <span>${esc(t.error)}</span>`;
      } catch (err) {
        out.innerHTML = `<span class="badge red">失败</span> <span>${esc(err.message)}</span>`;
      } finally { b.disabled = false; }
    }));
  }

  // ---------------------------------------------------------------- admins

  async function pageAdmins(el, alive) {
    const list = await api('GET', '/admins');
    if (!alive()) return;
    el.innerHTML = `
      <div class="toolbar"><span class="muted">超级管理员可以管理其他管理员；运营账号可以管理用户、分组、反馈和设置。</span><span class="spacer"></span><button class="btn-primary" id="ad-new">添加管理员</button></div>
      <div class="table-wrap"><table><thead><tr><th>账号</th><th>角色</th><th>创建时间</th><th>最近登录</th><th>操作</th></tr></thead><tbody>
        ${list.map(a => `<tr>
          <td><div class="primary">${esc(a.username)}${a.id === state.admin.id ? ' <span class="badge">我</span>' : ''}</div>${a.must_change_password ? '<div class="secondary">尚未修改初始密码</div>' : ''}</td>
          <td>${a.role === 'super' ? '<span class="badge blue">超级管理员</span>' : '<span class="badge">运营</span>'}</td>
          <td class="nowrap">${fmtTime(a.created_at)}</td>
          <td class="nowrap">${fmtTime(a.last_login_at)} <span class="secondary mono">${esc(a.last_login_ip || '')}</span></td>
          <td class="nowrap">${a.id === state.admin.id ? '' : `<button class="btn-link" data-reset="${a.id}">重置密码</button><button class="btn-link danger" data-del="${a.id}">删除</button>`}</td>
        </tr>`).join('')}
      </tbody></table></div>`;
    el.querySelector('#ad-new').addEventListener('click', () => {
      const pw = randomPassword() + randomPassword().slice(0, 4);
      openModal({
        title: '添加管理员',
        body: `<form>
          <label class="field"><span>账号</span><input name="username" required autocomplete="off"></label>
          <label class="field"><span>初始密码</span><input name="password" value="${pw}" autocomplete="off"><small>对方首次登录后必须修改</small></label>
          <label class="field"><span>角色</span><select name="role"><option value="operator">运营</option><option value="super">超级管理员</option></select></label></form>`,
        okText: '添加',
        onOk: async body => {
          const d = formData(body);
          await api('POST', '/admins', d);
          showSecret('管理员已添加', `${d.username} 的初始密码：`, d.password);
          renderPage();
        },
      });
    });
    el.querySelector('tbody').addEventListener('click', async e => {
      const t = e.target;
      const a = list.find(x => String(x.id) === (t.dataset.reset || t.dataset.del));
      if (!a) return;
      try {
        if (t.dataset.reset && await confirmBox('重置密码', `为 ${esc(a.username)} 生成新的随机密码？对方下次登录时需要修改。`, '重置')) {
          const r = await api('POST', `/admins/${a.id}/reset-password`);
          showSecret('密码已重置', `${a.username} 的新密码：`, r.password);
          renderPage();
        }
        if (t.dataset.del && await confirmBox('删除管理员', `确定删除 ${esc(a.username)}？`, '删除')) {
          await api('DELETE', `/admins/${a.id}`);
          toast('已删除');
          renderPage();
        }
      } catch (err) { fail(err); }
    });
  }

  // ---------------------------------------------------------------- audit

  let auditPage = 1;

  async function pageAudit(el, alive) {
    const r = await api('GET', '/audit-logs?' + new URLSearchParams({ page: auditPage, size: 30 }));
    if (!alive()) return;
    el.innerHTML = `<div class="table-wrap"><table><thead><tr><th>时间</th><th>管理员</th><th>操作</th><th>对象</th><th>详情</th><th>IP</th></tr></thead><tbody>
      ${r.items.length ? r.items.map(l => `<tr><td class="nowrap">${fmtTime(l.created_at)}</td><td>${esc(l.admin_name)}</td><td>${esc(l.action)}</td>
        <td>${esc(l.target)}</td><td class="small">${esc(l.detail)}</td><td class="mono">${esc(l.ip)}</td></tr>`).join('') : '<tr><td colspan="6" class="empty">暂无记录</td></tr>'}
      </tbody></table>${pager(r.total, r.page, r.size)}</div>`;
    el.querySelector('.pager').addEventListener('click', e => {
      if (e.target.dataset.page) { auditPage = Number(e.target.dataset.page); renderPage(); }
    });
  }

  boot();
})();
