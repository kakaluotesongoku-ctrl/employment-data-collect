const state = {
  token: null,
  user: null,
  dashboard: null,
  ws: null,
  wsRetryTimer: null,
  noticePage: 1,
  noticeSize: 5,
  noticeQuery: { keyword: '', createdFrom: '', createdTo: '' },
  provinceNoticePage: 1,
  provinceNoticeSize: 6,
  provinceNoticeQuery: { keyword: '', status: '', createdFrom: '', createdTo: '' },
  enterprisePage: 1,
  enterpriseSize: 5,
  enterpriseQuery: { keyword: '', city: '', nature: '', industry: '' },
  logPage: 1,
  logSize: 8,
  logQuery: { action: '', actorName: '', createdFrom: '', createdTo: '' },
  userPage: 1,
  userSize: 8,
  userQuery: { keyword: '', role: '', city: '', enabled: '' },
  myReportPage: 1,
  myReportSize: 5
};

const $ = (id) => document.getElementById(id);
const show = (el, visible) => el.classList.toggle('hidden', !visible);

async function request(path, options = {}) {
  const headers = Object.assign({ 'Content-Type': 'application/json' }, options.headers || {});
  if (state.token) headers['X-Auth-Token'] = state.token;
  const response = await fetch(path, { ...options, headers });
  const text = await response.text();
  let payload;
  try { payload = text ? JSON.parse(text) : {}; } catch { payload = { success: false, message: text }; }
  if (!response.ok || payload.success === false) {
    throw new Error(payload.message || `HTTP ${response.status}`);
  }
  return payload.data;
}

async function downloadFile(path, filename) {
  const headers = state.token ? { 'X-Auth-Token': state.token } : {};
  const response = await fetch(path, { headers });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `HTTP ${response.status}`);
  }
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

function appendFeed(message) {
  const feed = $('wsFeed');
  const item = document.createElement('div');
  item.className = 'item';
  item.innerHTML = `<h4>${escapeHtml(message.type || 'event')}</h4><p>${escapeHtml(JSON.stringify(message.payload || {}, null, 2))}</p>`;
  feed.prepend(item);
}

function setWsStatus(text) {
  const status = $('wsStatus');
  if (status) status.textContent = text;
}

function connectWebSocket() {
  if (!state.token) {
    $('loginStatus').textContent = '请先登录后再连接推送';
    return;
  }
  if (state.ws && state.ws.readyState === WebSocket.OPEN) {
    setWsStatus('已连接');
    return;
  }
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const socket = new WebSocket(`${protocol}//${location.host}/ws/updates?token=${encodeURIComponent(state.token)}`);
  state.ws = socket;
  setWsStatus('连接中');
  socket.onopen = () => {
    if (state.wsRetryTimer) {
      clearTimeout(state.wsRetryTimer);
      state.wsRetryTimer = null;
    }
    setWsStatus('已连接');
  };
  socket.onclose = () => {
    setWsStatus('已断开');
    if (state.token) {
      state.wsRetryTimer = setTimeout(() => connectWebSocket(), 3000);
    }
  };
  socket.onerror = () => setWsStatus('连接异常');
  socket.onmessage = (event) => {
    try {
      const message = JSON.parse(event.data);
      $('wsLastEvent').textContent = message.type || '-';
      appendFeed(message);
      if (message.type && message.type !== 'echo') {
        refreshDashboard().catch(() => {});
      }
    } catch {
      appendFeed({ type: 'raw', payload: { text: event.data } });
    }
  };
}

function disconnectWebSocket() {
  if (state.ws) {
    state.ws.close();
    state.ws = null;
  }
  if (state.wsRetryTimer) {
    clearTimeout(state.wsRetryTimer);
    state.wsRetryTimer = null;
  }
  setWsStatus('未连接');
}

function switchToTab(name) {
  document.querySelectorAll('.tab').forEach(item => item.classList.remove('active'));
  document.querySelectorAll('.tab-panel').forEach(item => item.classList.remove('active'));
  const tabBtn = $(`tabBtn-${name}`);
  const panel = $(`tab-${name}`);
  if (tabBtn) tabBtn.classList.add('active');
  if (panel) panel.classList.add('active');
}

function applyRoleView(role) {
  const map = {
    ENTERPRISE: ['enterprise'],
    CITY: ['city'],
    PROVINCE: ['province', 'city', 'enterprise']
  };
  const allowed = map[role] || ['enterprise'];
  ['enterprise', 'city', 'province'].forEach(name => {
    const btn = $(`tabBtn-${name}`);
    const panel = $(`tab-${name}`);
    const visible = allowed.includes(name);
    if (btn) show(btn, visible);
    if (panel) show(panel, visible);
  });
  switchToTab(allowed[0]);
}

function renderStats(summary, sampling, monitor) {
  const stats = $('stats');
  stats.innerHTML = '';
  const items = [];
  if (summary) {
    items.push(['企业总数', summary.enterpriseCount], ['建档期总岗位', summary.archivedJobs], ['调查期总岗位', summary.surveyJobs]);
  }
  if (sampling) {
    items.push(['取样企业数', sampling.total], ['活跃会话', monitor ? monitor.activeSessions : '-'], ['审计日志', monitor ? monitor.auditCount : '-']);
  }
  stats.innerHTML = items.map(([label, value]) => `<div class="stat"><span>${label}</span><strong>${value ?? '-'}</strong></div>`).join('');
}

function renderList(target, rows, empty = '暂无数据') {
  if (!rows || rows.length === 0) {
    target.innerHTML = `<div class="item"><p>${empty}</p></div>`;
    return;
  }
  target.innerHTML = rows.map(row => {
    if (row.title) {
      return `<div class="item"><h4>${row.title}</h4><p>${row.publisherName || ''} · ${row.status || ''} · ${row.createdAt || ''}</p><p>${row.content || ''}</p></div>`;
    }
    if (row.enterpriseName) {
      return `<div class="item"><h4>${row.enterpriseName}</h4><p>${row.periodName || ''} · ${row.status || ''} · ${row.archivedJobs ?? ''} → ${row.surveyJobs ?? ''}</p></div>`;
    }
    return `<div class="item"><pre>${escapeHtml(JSON.stringify(row, null, 2))}</pre></div>`;
  }).join('');
}

function renderSelectableList(target, rows, empty = '暂无数据') {
  if (!rows || rows.length === 0) {
    target.innerHTML = `<div class="item"><p>${empty}</p></div>`;
    return;
  }
  target.innerHTML = rows.map(row => `<label class="item selectable"><input type="checkbox" class="batch-report-checkbox" value="${row.id}"><span><strong>${row.enterpriseName}</strong><br>${row.periodName || ''} · ${row.status || ''} · ${row.archivedJobs ?? ''} → ${row.surveyJobs ?? ''}</span></label>`).join('');
}

function renderEnterpriseRows(target, rows) {
  if (!rows || rows.length === 0) {
    target.innerHTML = `<div class="item"><p>暂无企业数据</p></div>`;
    return;
  }
  target.innerHTML = rows.map(row => `<div class="item"><h4>${escapeHtml(row.enterpriseName || '-')}</h4><p>${escapeHtml(row.cityName || '-')} · ${escapeHtml(row.enterpriseNature || '-')} · ${escapeHtml(row.industry || '-')}</p><p>组织机构代码：${escapeHtml(row.orgCode || '-')} · 状态：${escapeHtml(row.status || '-')}</p></div>`).join('');
}

function renderUserRows(target, rows) {
  if (!rows || rows.length === 0) {
    target.innerHTML = `<div class="item"><p>暂无用户数据</p></div>`;
    return;
  }
  target.innerHTML = rows.map(row => `
    <div class="item">
      <h4>${escapeHtml(row.username || '-')}</h4>
      <p>ID:${row.id} · 角色:${escapeHtml(row.role || '-')} · 地市:${escapeHtml(row.cityName || '-')}</p>
      <p>手机号:${escapeHtml(row.phone || '-')} · 状态:${row.enabled ? '启用' : '禁用'} · 失败次数:${row.failedLoginCount || 0} · 锁定到:${escapeHtml(row.lockedUntil || '-')}</p>
      <div class="actions">
        <button class="ghost select-user-btn" data-user-id="${row.id}" data-user-role="${escapeHtml(row.role || '')}" data-user-city="${escapeHtml(row.cityName || '')}" data-user-enabled="${row.enabled}">选中用户</button>
      </div>
    </div>
  `).join('');
}

function renderLogRows(target, rows) {
  if (!rows || rows.length === 0) {
    target.innerHTML = `<div class="item"><p>暂无日志数据</p></div>`;
    return;
  }
  target.innerHTML = rows.map(row => `
    <div class="item">
      <h4>${escapeHtml(row.action || '-')}</h4>
      <p>ID:${row.id} · 目标:${escapeHtml(row.targetType || '-')}#${row.targetId} · 操作人:${escapeHtml(row.actorName || '-')}</p>
      <p>IP:${escapeHtml(row.clientIp || '-')} · 时间:${escapeHtml(row.createdAt || '-')}</p>
      <p>${escapeHtml(row.description || '-')}</p>
    </div>
  `).join('');
}

function renderProvinceNoticeRows(target, rows) {
  if (!rows || rows.length === 0) {
    target.innerHTML = `<div class="item"><p>暂无通知数据</p></div>`;
    return;
  }
  target.innerHTML = rows.map(row => `
    <div class="item">
      <h4>${escapeHtml(row.title || '-')}</h4>
      <p>ID:${row.id} · 状态:${escapeHtml(row.status || '-')} · 发布人:${escapeHtml(row.publisherName || '-')}</p>
      <p>适用范围:${row.appliesToAll ? '全省' : escapeHtml((row.targetCities || []).join(','))} · 时间:${escapeHtml(row.createdAt || '-')}</p>
      <p>${escapeHtml(row.content || '-')}</p>
      <div class="actions">
        <button class="ghost select-province-notice-btn" data-notice-id="${row.id}">选中编辑</button>
      </div>
    </div>
  `).join('');
}

function renderPager(prefix, pageData) {
  const label = $(`${prefix}PageInfo`);
  const prevBtn = $(`${prefix}PrevBtn`);
  const nextBtn = $(`${prefix}NextBtn`);
  if (!label || !prevBtn || !nextBtn) return;
  const current = pageData.page || 1;
  const totalPages = pageData.totalPages || 1;
  const total = pageData.total || 0;
  label.textContent = `第 ${current}/${totalPages} 页，共 ${total} 条`;
  prevBtn.disabled = current <= 1;
  nextBtn.disabled = current >= totalPages;
}

function escapeHtml(text) {
  return String(text)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');
}

async function refreshDashboard() {
  const data = await request('/api/dashboard');
  state.dashboard = data;
  state.user = data.user;
  $('currentUserName').textContent = data.user.username;
  $('currentUserRole').textContent = `${data.user.role} · ${data.user.cityName || ''}`;
  applyRoleView(data.user.role);
  renderStats(data.summary, data.sampling, data.monitor);
  renderList($('reports'), data.reports || [], '暂无报表');

  const periods = await request('/api/periods');
  const options = periods.map(p => `<option value="${p.id}">${p.name}</option>`).join('');
  $('leftPeriod').innerHTML = options;
  $('rightPeriod').innerHTML = options;
  await loadNoticesPage(1);
  await loadEnterprisesPage(1);
  await loadMyReportsPage(1);
  if (data.user.role === 'PROVINCE') {
    await loadUsersPage(1);
    await loadLogsPage(1);
    await loadProvinceNoticesPage(1);
  }
}

async function login() {
  const body = {
    username: $('username').value,
    password: $('password').value,
    role: $('role').value,
    smsCode: $('smsCode').value || null
  };
  try {
    const response = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    const payload = await response.json();
    if (payload.data && payload.data.requiresSmsCode) {
      $('loginStatus').textContent = `需要短信验证码。演示验证码：${payload.data.smsCodeHint || '未返回'}`;
      return;
    }
    if (!response.ok || payload.success === false) {
      throw new Error(payload.message || `HTTP ${response.status}`);
    }
    const result = payload.data;
    state.token = result.token;
    $('loginStatus').textContent = '登录成功';
    show($('loginPanel'), true);
    show($('dashboard'), true);
    show($('logoutBtn'), true);
    show($('refreshBtn'), true);
    await refreshDashboard();
    connectWebSocket();
  } catch (error) {
    $('loginStatus').textContent = error.message;
  }
}

async function logout() {
  if (state.token) {
    await request('/api/auth/logout', { method: 'POST', body: '{}' });
  }
  state.token = null;
  state.user = null;
  show($('dashboard'), false);
  show($('logoutBtn'), false);
  show($('refreshBtn'), false);
  disconnectWebSocket();
  $('loginStatus').textContent = '已退出登录';
}

async function resetPassword() {
  const body = {
    username: $('resetUsername').value,
    phone: $('resetPhone').value,
    newPassword: $('resetPassword').value,
    confirmPassword: $('resetPasswordConfirm').value
  };
  await request('/api/auth/reset-password', { method: 'POST', body: JSON.stringify(body) });
  $('loginStatus').textContent = '密码已重置，请重新登录';
}

async function searchNotices() {
  state.noticeQuery.keyword = $('noticeKeywordFilter').value.trim();
  state.noticeQuery.createdFrom = $('noticeFromFilter').value;
  state.noticeQuery.createdTo = $('noticeToFilter').value;
  await loadNoticesPage(1);
}

async function clearNoticesFilter() {
  $('noticeKeywordFilter').value = '';
  $('noticeFromFilter').value = '';
  $('noticeToFilter').value = '';
  state.noticeQuery = { keyword: '', createdFrom: '', createdTo: '' };
  await loadNoticesPage(1);
}

async function loadNoticesPage(page) {
  state.noticePage = page;
  const params = new URLSearchParams();
  if (state.noticeQuery.keyword) params.set('keyword', state.noticeQuery.keyword);
  if (state.noticeQuery.createdFrom) params.set('createdFrom', state.noticeQuery.createdFrom);
  if (state.noticeQuery.createdTo) params.set('createdTo', state.noticeQuery.createdTo);
  params.set('page', String(state.noticePage));
  params.set('size', String(state.noticeSize));
  const data = await request(`/api/notices?${params.toString()}`);
  renderList($('notices'), data.items || [], '暂无通知');
  renderPager('notice', data);
  $('loginStatus').textContent = `通知查询完成：${data.total} 条`;
}

async function searchEnterprises() {
  state.enterpriseQuery.keyword = $('enterpriseKeywordFilter').value.trim();
  state.enterpriseQuery.city = $('enterpriseCityFilter').value.trim();
  state.enterpriseQuery.nature = $('enterpriseNatureFilter').value.trim();
  state.enterpriseQuery.industry = $('enterpriseIndustryFilter').value.trim();
  await loadEnterprisesPage(1);
}

async function clearEnterpriseFilter() {
  $('enterpriseKeywordFilter').value = '';
  $('enterpriseCityFilter').value = '';
  $('enterpriseNatureFilter').value = '';
  $('enterpriseIndustryFilter').value = '';
  state.enterpriseQuery = { keyword: '', city: '', nature: '', industry: '' };
  await loadEnterprisesPage(1);
}

async function loadEnterprisesPage(page) {
  state.enterprisePage = page;
  const params = new URLSearchParams();
  if (state.enterpriseQuery.keyword) params.set('keyword', state.enterpriseQuery.keyword);
  if (state.enterpriseQuery.city) params.set('city', state.enterpriseQuery.city);
  if (state.enterpriseQuery.nature) params.set('nature', state.enterpriseQuery.nature);
  if (state.enterpriseQuery.industry) params.set('industry', state.enterpriseQuery.industry);
  params.set('page', String(state.enterprisePage));
  params.set('size', String(state.enterpriseSize));
  const data = await request(`/api/enterprises?${params.toString()}`);
  renderEnterpriseRows($('enterprisesResult'), data.items || []);
  renderPager('enterprise', data);
}

async function loadMyReportsPage(page) {
  state.myReportPage = page;
  const params = new URLSearchParams();
  params.set('page', String(state.myReportPage));
  params.set('size', String(state.myReportSize));
  const data = await request(`/api/reports/page?${params.toString()}`);
  renderList($('myReports'), data.items || [], '暂无我的报表');
  renderPager('myReport', data);
}

async function searchLogs() {
  state.logQuery.action = $('logActionFilter').value.trim();
  state.logQuery.actorName = $('logActorFilter').value.trim();
  state.logQuery.createdFrom = $('logFromFilter').value;
  state.logQuery.createdTo = $('logToFilter').value;
  await loadLogsPage(1);
}

async function clearLogFilter() {
  $('logActionFilter').value = '';
  $('logActorFilter').value = '';
  $('logFromFilter').value = '';
  $('logToFilter').value = '';
  state.logQuery = { action: '', actorName: '', createdFrom: '', createdTo: '' };
  await loadLogsPage(1);
}

async function loadLogsPage(page) {
  if (!state.user || state.user.role !== 'PROVINCE') {
    return;
  }
  state.logPage = page;
  const params = new URLSearchParams();
  if (state.logQuery.action) params.set('action', state.logQuery.action);
  if (state.logQuery.actorName) params.set('actorName', state.logQuery.actorName);
  if (state.logQuery.createdFrom) params.set('createdFrom', state.logQuery.createdFrom);
  if (state.logQuery.createdTo) params.set('createdTo', state.logQuery.createdTo);
  params.set('page', String(state.logPage));
  params.set('size', String(state.logSize));
  const data = await request(`/api/logs/page?${params.toString()}`);
  renderLogRows($('logsResult'), data.items || []);
  renderPager('log', data);
}

async function searchProvinceNotices() {
  state.provinceNoticeQuery.keyword = $('provinceNoticeKeyword').value.trim();
  state.provinceNoticeQuery.status = $('provinceNoticeStatus').value;
  state.provinceNoticeQuery.createdFrom = $('provinceNoticeFrom').value;
  state.provinceNoticeQuery.createdTo = $('provinceNoticeTo').value;
  await loadProvinceNoticesPage(1);
}

async function clearProvinceNoticeFilter() {
  $('provinceNoticeKeyword').value = '';
  $('provinceNoticeStatus').value = '';
  $('provinceNoticeFrom').value = '';
  $('provinceNoticeTo').value = '';
  state.provinceNoticeQuery = { keyword: '', status: '', createdFrom: '', createdTo: '' };
  await loadProvinceNoticesPage(1);
}

async function loadProvinceNoticesPage(page) {
  if (!state.user || state.user.role !== 'PROVINCE') {
    return;
  }
  state.provinceNoticePage = page;
  const params = new URLSearchParams();
  if (state.provinceNoticeQuery.keyword) params.set('keyword', state.provinceNoticeQuery.keyword);
  if (state.provinceNoticeQuery.status) params.set('status', state.provinceNoticeQuery.status);
  if (state.provinceNoticeQuery.createdFrom) params.set('createdFrom', state.provinceNoticeQuery.createdFrom);
  if (state.provinceNoticeQuery.createdTo) params.set('createdTo', state.provinceNoticeQuery.createdTo);
  params.set('page', String(state.provinceNoticePage));
  params.set('size', String(state.provinceNoticeSize));
  const data = await request(`/api/notices?${params.toString()}`);
  renderProvinceNoticeRows($('provinceNotices'), data.items || []);
  renderPager('provinceNotice', data);
}

async function saveProvinceNotice(publishNow) {
  const idRaw = $('provinceNoticeId').value;
  const body = {
    noticeId: idRaw ? Number(idRaw) : null,
    title: $('provinceNoticeTitle').value,
    content: $('provinceNoticeContent').value,
    appliesToAll: false,
    targetCities: $('provinceNoticeCities').value.split(',').map(v => v.trim()).filter(Boolean)
  };
  const data = await request(`/api/notices/save?publishNow=${publishNow}`, { method: 'POST', body: JSON.stringify(body) });
  $('provinceNoticeId').value = data.id;
  $('loginStatus').textContent = publishNow ? '省级通知发布成功' : '省级通知草稿已保存';
  await loadProvinceNoticesPage(state.provinceNoticePage);
}

async function deleteProvinceNotice() {
  const id = Number($('provinceNoticeId').value || 0);
  if (!id) throw new Error('请先选择要删除的通知');
  await request(`/api/notices/${id}/delete`, { method: 'POST' });
  $('loginStatus').textContent = '省级通知已删除';
  $('provinceNoticeId').value = '';
  await loadProvinceNoticesPage(state.provinceNoticePage);
}

async function exportProvinceNotices() {
  await downloadFile('/api/dashboard/export/notices', 'notices.xlsx');
  $('loginStatus').textContent = '通知 Excel 已导出';
}

async function exportLogsCsv() {
  const params = new URLSearchParams();
  if (state.logQuery.action) params.set('action', state.logQuery.action);
  if (state.logQuery.actorName) params.set('actorName', state.logQuery.actorName);
  if (state.logQuery.createdFrom) params.set('createdFrom', state.logQuery.createdFrom);
  if (state.logQuery.createdTo) params.set('createdTo', state.logQuery.createdTo);
  await downloadFile(`/api/logs/export-csv?${params.toString()}`, 'audit-logs.csv');
  $('loginStatus').textContent = '日志 CSV 已导出';
}

async function searchUsers() {
  state.userQuery.keyword = $('userKeywordFilter').value.trim();
  state.userQuery.role = $('userRoleFilter').value;
  state.userQuery.city = $('userCityFilter').value.trim();
  state.userQuery.enabled = $('userEnabledFilter').value;
  await loadUsersPage(1);
}

async function clearUsersFilter() {
  $('userKeywordFilter').value = '';
  $('userRoleFilter').value = '';
  $('userCityFilter').value = '';
  $('userEnabledFilter').value = '';
  state.userQuery = { keyword: '', role: '', city: '', enabled: '' };
  await loadUsersPage(1);
}

async function loadUsersPage(page) {
  if (!state.user || state.user.role !== 'PROVINCE') {
    return;
  }
  state.userPage = page;
  const params = new URLSearchParams();
  if (state.userQuery.keyword) params.set('keyword', state.userQuery.keyword);
  if (state.userQuery.role) params.set('role', state.userQuery.role);
  if (state.userQuery.city) params.set('city', state.userQuery.city);
  if (state.userQuery.enabled) params.set('enabled', state.userQuery.enabled);
  params.set('page', String(state.userPage));
  params.set('size', String(state.userSize));
  const data = await request(`/api/users?${params.toString()}`);
  renderUserRows($('usersResult'), data.items || []);
  renderPager('user', data);
}

function selectedManageUserId() {
  const raw = $('manageUserId').value;
  const id = Number(raw);
  if (!id) {
    throw new Error('请先选择目标用户');
  }
  return id;
}

async function updateUserRole() {
  const body = {
    userId: selectedManageUserId(),
    role: $('manageUserRole').value,
    cityName: $('manageUserCity').value
  };
  await request('/api/users/role', { method: 'POST', body: JSON.stringify(body) });
  $('loginStatus').textContent = '用户角色已更新';
  await loadUsersPage(state.userPage);
}

async function toggleUserEnabled() {
  const body = {
    userId: selectedManageUserId(),
    enabled: $('manageUserEnabled').value === 'true'
  };
  await request('/api/users/enabled', { method: 'POST', body: JSON.stringify(body) });
  $('loginStatus').textContent = '用户状态已更新';
  await loadUsersPage(state.userPage);
}

async function unlockUser() {
  const body = { userId: selectedManageUserId() };
  await request('/api/users/unlock', { method: 'POST', body: JSON.stringify(body) });
  $('loginStatus').textContent = '用户已解锁';
  await loadUsersPage(state.userPage);
}

async function adminResetUserPassword() {
  const body = {
    userId: selectedManageUserId(),
    newPassword: $('manageUserPassword').value,
    confirmPassword: $('manageUserPasswordConfirm').value
  };
  await request('/api/users/reset-password-admin', { method: 'POST', body: JSON.stringify(body) });
  $('loginStatus').textContent = '管理员重置密码成功';
  $('manageUserPassword').value = '';
  $('manageUserPasswordConfirm').value = '';
}

async function saveEnterprise(submit) {
  const body = {
    regionProvince: '云南省',
    cityName: $('cityName').value,
    countyName: '默认县区',
    orgCode: $('orgCode').value,
    enterpriseName: $('enterpriseName').value,
    enterpriseNature: $('enterpriseNature').value,
    industry: $('industry').value,
    contactName: $('contactName').value,
    contactPhone: $('contactPhone').value,
    address: '演示地址'
  };
  const data = await request(`/api/enterprises/save?submit=${submit}`, { method: 'POST', body: JSON.stringify(body) });
  $('loginStatus').textContent = `备案${submit ? '并提交' : '保存'}成功：${data.enterpriseName}`;
  await refreshDashboard();
}

async function saveReport(submit) {
  const periods = await request('/api/periods');
  const currentPeriod = periods[periods.length - 1];
  const body = {
    periodId: currentPeriod.id,
    archivedJobs: Number($('archivedJobs').value),
    surveyJobs: Number($('surveyJobs').value),
    otherReason: $('otherReason').value,
    decreaseType: $('decreaseType').value,
    mainReason: $('mainReason').value,
    mainReasonDescription: $('mainReasonDescription').value,
    secondaryReason: '市场变化',
    secondaryReasonDescription: '二级原因说明',
    thirdReason: '其他',
    thirdReasonDescription: '三级原因说明'
  };
  const data = await request(`/api/reports/save?submit=${submit}`, { method: 'POST', body: JSON.stringify(body) });
  $('loginStatus').textContent = `报表${submit ? '提交' : '保存'}成功：${data.status}`;
  await refreshDashboard();
}

async function loadCityReports() {
  const data = await request('/api/reports?status=PENDING_CITY_REVIEW');
  renderSelectableList($('cityReports'), data, '暂无待审报表');
  $('cityReports').dataset.items = JSON.stringify(data);
}

function selectedCityReportIds() {
  return Array.from(document.querySelectorAll('.batch-report-checkbox:checked')).map(input => Number(input.value));
}

async function cityProcess(approved) {
  const items = JSON.parse($('cityReports').dataset.items || '[]');
  if (!items.length) throw new Error('没有可处理的报表');
  const target = items[0];
  const reason = approved ? '' : prompt('请输入退回理由', '数据需补充') || '';
  await request(`/api/reports/${target.id}/city-review?approved=${approved}&reason=${encodeURIComponent(reason)}`, { method: 'POST' });
  $('loginStatus').textContent = approved ? '市级审核通过' : '市级审核退回';
  await loadCityReports();
  await refreshDashboard();
}

async function cityBatchProcess(approved) {
  const reportIds = selectedCityReportIds();
  if (!reportIds.length) throw new Error('请先勾选待审报表');
  const body = { reportIds, approved, reason: approved ? '' : '批量退回，需补充材料' };
  const data = await request('/api/reports/city-review/batch', { method: 'POST', body: JSON.stringify(body) });
  $('loginStatus').textContent = `批量审核完成：成功 ${data.successIds.length} 条，失败 ${data.failedMessages.length} 条`;
  await loadCityReports();
  await refreshDashboard();
}

async function loadProvinceReports() {
  const data = await request('/api/reports?status=PENDING_PROVINCE_REVIEW');
  renderSelectableList($('provinceReports'), data, '暂无省级待审报表');
  $('provinceReports').dataset.items = JSON.stringify(data);
}

function selectedProvinceReportIds() {
  const container = $('provinceReports');
  if (!container) return [];
  return Array.from(container.querySelectorAll('.batch-report-checkbox:checked')).map(input => Number(input.value));
}

async function provinceBatchProcess(approved) {
  const reportIds = selectedProvinceReportIds();
  if (!reportIds.length) throw new Error('请先勾选省级待审报表');
  const body = { reportIds, approved, reason: approved ? '' : '批量退回，需补充省级核验材料' };
  const data = await request('/api/reports/province-review/batch', { method: 'POST', body: JSON.stringify(body) });
  $('loginStatus').textContent = `省级批量审核完成：成功 ${data.successIds.length} 条，失败 ${data.failedMessages.length} 条`;
  await loadProvinceReports();
  await refreshDashboard();
}

async function saveNotice() {
  const body = {
    title: $('noticeTitle').value,
    content: $('noticeContent').value,
    appliesToAll: false,
    targetCities: $('noticeCities').value.split(',').map(v => v.trim()).filter(Boolean)
  };
  await request('/api/notices/save?publishNow=true', { method: 'POST', body: JSON.stringify(body) });
  $('loginStatus').textContent = '通知发布成功';
  await refreshDashboard();
}

async function createUser() {
  const body = {
    username: $('newUsername').value,
    password: $('newPassword').value,
    role: $('newRole').value,
    cityName: $('newUserCity').value,
    phone: $('newUserPhone').value
  };
  const data = await request('/api/users', { method: 'POST', body: JSON.stringify(body) });
  $('loginStatus').textContent = `账号创建成功：${data.user.username}`;
  await refreshDashboard();
}

async function savePeriod() {
  const body = {
    name: $('periodName').value,
    startDate: $('periodStart').value,
    endDate: $('periodEnd').value,
    submissionStart: $('periodSubmitStart').value,
    submissionEnd: $('periodSubmitEnd').value,
    active: $('periodActive').checked
  };
  const data = await request('/api/periods', { method: 'POST', body: JSON.stringify(body) });
  $('loginStatus').textContent = `调查期保存成功：${data.name}`;
  await refreshDashboard();
}

async function exportReportsCsv() {
  await downloadFile('/api/dashboard/export/reports-csv', 'reports.csv');
  $('loginStatus').textContent = '报表 CSV 已导出';
}

async function exportEnterprisesCsv() {
  await downloadFile('/api/dashboard/export/enterprises-csv', 'enterprises.csv');
  $('loginStatus').textContent = '企业 CSV 已导出';
}

async function exportSummaryCsv() {
  const periodId = periodsSelectValue();
  await downloadFile(`/api/dashboard/export/summary-csv?periodId=${periodId}`, 'summary.csv');
  $('loginStatus').textContent = '汇总 CSV 已导出';
}

async function exportCustomReportsCsv() {
  const fields = $('customReportFields').value.trim();
  const params = new URLSearchParams();
  if (fields) params.set('fields', fields);
  await downloadFile(`/api/dashboard/export/reports-custom-csv?${params.toString()}`, 'reports-custom.csv');
  $('loginStatus').textContent = '自定义报表 CSV 已导出';
}

async function exportCustomEnterprisesCsv() {
  const fields = $('customEnterpriseFields').value.trim();
  const params = new URLSearchParams();
  if (fields) params.set('fields', fields);
  await downloadFile(`/api/dashboard/export/enterprises-custom-csv?${params.toString()}`, 'enterprises-custom.csv');
  $('loginStatus').textContent = '自定义企业 CSV 已导出';
}

async function provinceAction(action) {
  const reports = await request('/api/reports');
  const enterprisePage = await request('/api/enterprises?page=1&size=100');
  const enterprises = enterprisePage.items || [];
  if (action === 'enterprise') {
    if (!enterprises.length) throw new Error('没有备案可审核');
    await request(`/api/enterprises/${enterprises[0].id}/review?approved=true`, { method: 'POST' });
    $('loginStatus').textContent = '备案审核通过';
  }
  if (action === 'approve') {
    const target = reports.find(r => r.status === 'PENDING_PROVINCE_REVIEW');
    if (!target) throw new Error('没有待省级审核报表');
    await request(`/api/reports/${target.id}/province-review?approved=true`, { method: 'POST' });
    $('loginStatus').textContent = '省级审核通过';
  }
  if (action === 'correct') {
    const target = reports.find(r => r.status === 'PENDING_PROVINCE_REVIEW') || reports[0];
    if (!target) throw new Error('没有可修正报表');
    const body = {
      reportId: target.id,
      periodId: periodsSelectValue(),
      archivedJobs: target.archivedJobs,
      surveyJobs: target.surveyJobs,
      otherReason: target.otherReason || '省级代填',
      decreaseType: target.decreaseType || '人工修正',
      mainReason: target.mainReason || '人工修正',
      mainReasonDescription: target.mainReasonDescription || '省级修正说明',
      secondaryReason: target.secondaryReason || '',
      secondaryReasonDescription: target.secondaryReasonDescription || '',
      thirdReason: target.thirdReason || '',
      thirdReasonDescription: target.thirdReasonDescription || '',
      adjustReason: '省级代填修正'
    };
    await request(`/api/reports/${target.id}/province-correct`, { method: 'POST', body: JSON.stringify(body) });
    $('loginStatus').textContent = '省级代填/修正完成';
  }
  await refreshDashboard();
}

async function publishMinistry() {
  try {
    const periodId = periodsSelectValue();
    const data = await request(`/api/reports/publish?periodId=${periodId}`, { method: 'POST' });
    $('loginStatus').textContent = `部级上报完成：${data.total} 条，新增 ${data.changed} 条`;
    await refreshDashboard();
  } catch (error) {
    $('loginStatus').textContent = error.message;
  }
}

async function previewMinistry() {
  try {
    const periodId = periodsSelectValue();
    const data = await request(`/api/ministry/preview?periodId=${periodId}`);
    $('analysis').innerHTML = `<div class="item"><h4>部级格式预览</h4><p>调查期：${data.periodName}，记录数：${data.records.length}，已上报：${data.changed}</p><pre>${escapeHtml(JSON.stringify(data.records[0] || {}, null, 2))}</pre></div>`;
  } catch (error) {
    $('analysis').innerHTML = `<div class="item"><h4>部级格式预览</h4><p>${escapeHtml(error.message)}</p></div>`;
  }
}

async function exportMinistryXml() {
  try {
    const periodId = periodsSelectValue();
    await downloadFile(`/api/ministry/export?periodId=${periodId}`, 'ministry-report.xml');
    $('loginStatus').textContent = '部级 XML 已导出';
  } catch (error) {
    $('loginStatus').textContent = error.message;
  }
}

async function exportMinistryExcel() {
  try {
    const periodId = periodsSelectValue();
    await downloadFile(`/api/ministry/export-excel?periodId=${periodId}`, 'ministry-report.xlsx');
    $('loginStatus').textContent = '部级 Excel 已导出';
  } catch (error) {
    $('loginStatus').textContent = error.message;
  }
}

async function exportMinistryJson() {
  try {
    const periodId = periodsSelectValue();
    const data = await request(`/api/ministry/export-json?periodId=${periodId}&publish=false`);
    $('analysis').innerHTML = `<div class="item"><h4>部级 JSON 预览</h4><p>调查期：${data.periodName}，记录数：${data.records.length}</p><pre>${escapeHtml(JSON.stringify(data.records.slice(0, 3), null, 2))}</pre></div>`;
    $('loginStatus').textContent = '部级 JSON 已生成（页面预览）';
  } catch (error) {
    $('loginStatus').textContent = error.message;
  }
}

function periodsSelectValue() {
  const value = $('rightPeriod').value || $('leftPeriod').value;
  return Number(value);
}

async function showSummary() {
  const periodId = Number($('rightPeriod').value || 0) || undefined;
  const data = await request(`/api/summary${periodId ? `?periodId=${periodId}` : ''}`);
  $('analysis').innerHTML = `<div class="item"><h4>汇总结果</h4><p>调查期：${data.periodName}</p><p>企业：${data.enterpriseCount}，建档期岗位：${data.archivedJobs}，调查期岗位：${data.surveyJobs}，变化率：${data.changeRatio.toFixed(2)}%</p></div>`;
}

async function showSampling() {
  const data = await request('/api/sampling');
  $('analysis').innerHTML = `<div class="item"><h4>取样分析</h4><p>${data.rows.map(r => `${r.cityName}:${r.enterpriseCount}(${r.ratio.toFixed(2)}%)`).join('；')}</p></div>`;
}

async function compare() {
  const body = {
    leftPeriodId: Number($('leftPeriod').value),
    rightPeriodId: Number($('rightPeriod').value),
    dimensions: ['city'],
    cityName: '',
    enterpriseNature: '',
    industry: ''
  };
  const data = await request('/api/comparison', { method: 'POST', body: JSON.stringify(body) });
  $('analysis').innerHTML = `<div class="item"><h4>对比分析</h4><p>${data.rows.map(r => `${r.groupKey}: ${r.leftSurveyJobs} → ${r.rightSurveyJobs}`).join('；')}</p></div>`;
}

async function trend() {
  const periods = await request('/api/periods');
  const body = {
    periodIds: periods.slice(-3).map(p => p.id),
    cityName: '',
    enterpriseNature: '',
    industry: '',
    note: ['示例趋势说明']
  };
  const data = await request('/api/trend', { method: 'POST', body: JSON.stringify(body) });
  $('analysis').innerHTML = `<div class="item"><h4>趋势分析</h4><p>${data.rows.map(r => `${r.periodName}:${r.changeRatio.toFixed(2)}%`).join('；')}</p></div>`;
}

async function monitor() {
  const data = await request('/api/monitor');
  $('analysis').innerHTML = `<div class="item"><h4>系统监控</h4><p>CPU：${data.processors} 核，已用内存：${Math.round(data.usedMemory / 1024 / 1024)} MB，最大内存：${Math.round(data.maxMemory / 1024 / 1024)} MB，在线会话：${data.activeSessions}</p></div>`;
}

function bindTabs() {
  document.querySelectorAll('.tab').forEach(tab => {
    tab.addEventListener('click', () => {
      document.querySelectorAll('.tab').forEach(item => item.classList.remove('active'));
      document.querySelectorAll('.tab-panel').forEach(item => item.classList.remove('active'));
      tab.classList.add('active');
      $(`tab-${tab.dataset.tab}`).classList.add('active');
    });
  });
}

function bindUserSelection() {
  const container = $('usersResult');
  if (!container) return;
  container.addEventListener('click', (event) => {
    const button = event.target.closest('.select-user-btn');
    if (!button) return;
    $('manageUserId').value = button.dataset.userId || '';
    if (button.dataset.userRole) {
      $('manageUserRole').value = button.dataset.userRole;
    }
    $('manageUserCity').value = button.dataset.userCity || '';
    $('manageUserEnabled').value = button.dataset.userEnabled === 'false' ? 'false' : 'true';
  });
}

function bindProvinceNoticeSelection() {
  const container = $('provinceNotices');
  if (!container) return;
  container.addEventListener('click', async (event) => {
    const button = event.target.closest('.select-province-notice-btn');
    if (!button) return;
    const id = Number(button.dataset.noticeId || 0);
    if (!id) return;
    const data = await request(`/api/notices?page=1&size=1&keyword=${id}`);
    const row = (data.items || []).find(item => item.id === id);
    if (!row) return;
    $('provinceNoticeId').value = row.id;
    $('provinceNoticeTitle').value = row.title || '';
    $('provinceNoticeContent').value = row.content || '';
    $('provinceNoticeCities').value = (row.targetCities || []).join(',');
  });
}

function bindActions() {
  $('loginBtn').addEventListener('click', login);
  $('logoutBtn').addEventListener('click', logout);
  $('refreshBtn').addEventListener('click', refreshDashboard);
  $('resetPasswordBtn').addEventListener('click', resetPassword);
  $('saveEnterpriseBtn').addEventListener('click', () => saveEnterprise(false));
  $('submitEnterpriseBtn').addEventListener('click', () => saveEnterprise(true));
  $('saveReportBtn').addEventListener('click', () => saveReport(false));
  $('submitReportBtn').addEventListener('click', () => saveReport(true));
  $('loadCityReportsBtn').addEventListener('click', loadCityReports);
  $('cityApproveBtn').addEventListener('click', () => cityProcess(true));
  $('cityRejectBtn').addEventListener('click', () => cityProcess(false));
  $('cityBatchApproveBtn').addEventListener('click', () => cityBatchProcess(true));
  $('cityBatchRejectBtn').addEventListener('click', () => cityBatchProcess(false));
  $('loadProvinceReportsBtn').addEventListener('click', loadProvinceReports);
  $('provinceBatchApproveBtn').addEventListener('click', () => provinceBatchProcess(true));
  $('provinceBatchRejectBtn').addEventListener('click', () => provinceBatchProcess(false));
  $('saveNoticeBtn').addEventListener('click', saveNotice);
  $('createUserBtn').addEventListener('click', createUser);
  $('savePeriodBtn').addEventListener('click', savePeriod);
  $('reviewEnterpriseBtn').addEventListener('click', () => provinceAction('enterprise'));
  $('provinceApproveBtn').addEventListener('click', () => provinceAction('approve'));
  $('provinceCorrectBtn').addEventListener('click', () => provinceAction('correct'));
  $('publishMinistryBtn').addEventListener('click', publishMinistry);
  $('exportMinistryBtn').addEventListener('click', exportMinistryXml);
  $('previewMinistryBtn').addEventListener('click', previewMinistry);
  $('exportMinistryExcelBtn').addEventListener('click', exportMinistryExcel);
  $('exportMinistryJsonBtn').addEventListener('click', exportMinistryJson);
  $('exportReportsCsvBtn').addEventListener('click', exportReportsCsv);
  $('exportEnterprisesCsvBtn').addEventListener('click', exportEnterprisesCsv);
  $('exportSummaryCsvBtn').addEventListener('click', exportSummaryCsv);
  $('exportCustomReportsBtn').addEventListener('click', exportCustomReportsCsv);
  $('exportCustomEnterprisesBtn').addEventListener('click', exportCustomEnterprisesCsv);
  $('searchNoticesBtn').addEventListener('click', searchNotices);
  $('clearNoticesBtn').addEventListener('click', clearNoticesFilter);
  $('noticePrevBtn').addEventListener('click', () => loadNoticesPage(Math.max(1, state.noticePage - 1)));
  $('noticeNextBtn').addEventListener('click', () => loadNoticesPage(state.noticePage + 1));
  $('searchEnterprisesBtn').addEventListener('click', searchEnterprises);
  $('clearEnterprisesBtn').addEventListener('click', clearEnterpriseFilter);
  $('enterprisePrevBtn').addEventListener('click', () => loadEnterprisesPage(Math.max(1, state.enterprisePage - 1)));
  $('enterpriseNextBtn').addEventListener('click', () => loadEnterprisesPage(state.enterprisePage + 1));
  $('loadUsersBtn').addEventListener('click', () => loadUsersPage(1));
  $('searchUsersBtn').addEventListener('click', searchUsers);
  $('clearUsersBtn').addEventListener('click', clearUsersFilter);
  $('userPrevBtn').addEventListener('click', () => loadUsersPage(Math.max(1, state.userPage - 1)));
  $('userNextBtn').addEventListener('click', () => loadUsersPage(state.userPage + 1));
  $('updateUserRoleBtn').addEventListener('click', updateUserRole);
  $('toggleUserEnabledBtn').addEventListener('click', toggleUserEnabled);
  $('unlockUserBtn').addEventListener('click', unlockUser);
  $('adminResetUserPwdBtn').addEventListener('click', adminResetUserPassword);
  $('searchLogsBtn').addEventListener('click', searchLogs);
  $('clearLogsBtn').addEventListener('click', clearLogFilter);
  $('logPrevBtn').addEventListener('click', () => loadLogsPage(Math.max(1, state.logPage - 1)));
  $('logNextBtn').addEventListener('click', () => loadLogsPage(state.logPage + 1));
  $('exportLogsBtn').addEventListener('click', exportLogsCsv);
  $('searchProvinceNoticesBtn').addEventListener('click', searchProvinceNotices);
  $('clearProvinceNoticesBtn').addEventListener('click', clearProvinceNoticeFilter);
  $('provinceNoticePrevBtn').addEventListener('click', () => loadProvinceNoticesPage(Math.max(1, state.provinceNoticePage - 1)));
  $('provinceNoticeNextBtn').addEventListener('click', () => loadProvinceNoticesPage(state.provinceNoticePage + 1));
  $('saveProvinceNoticeDraftBtn').addEventListener('click', () => saveProvinceNotice(false));
  $('publishProvinceNoticeBtn').addEventListener('click', () => saveProvinceNotice(true));
  $('deleteProvinceNoticeBtn').addEventListener('click', deleteProvinceNotice);
  $('exportProvinceNoticesBtn').addEventListener('click', exportProvinceNotices);
  $('myReportPrevBtn').addEventListener('click', () => loadMyReportsPage(Math.max(1, state.myReportPage - 1)));
  $('myReportNextBtn').addEventListener('click', () => loadMyReportsPage(state.myReportPage + 1));
  $('showSummaryBtn').addEventListener('click', showSummary);
  $('showSamplingBtn').addEventListener('click', showSampling);
  $('compareBtn').addEventListener('click', compare);
  $('trendBtn').addEventListener('click', trend);
  $('monitorBtn').addEventListener('click', monitor);
  $('connectWsBtn').addEventListener('click', connectWebSocket);
  $('disconnectWsBtn').addEventListener('click', disconnectWebSocket);
  $('loadProvinceDataBtn').addEventListener('click', refreshDashboard);
}

bindTabs();
bindUserSelection();
bindProvinceNoticeSelection();
bindActions();
