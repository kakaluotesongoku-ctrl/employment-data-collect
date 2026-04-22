const state = { token: null, user: null, dashboard: null, ws: null, wsRetryTimer: null };

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
  renderList($('notices'), data.notices || [], '暂无通知');
  renderList($('reports'), data.reports || [], '暂无报表');

  const periods = await request('/api/periods');
  const options = periods.map(p => `<option value="${p.id}">${p.name}</option>`).join('');
  $('leftPeriod').innerHTML = options;
  $('rightPeriod').innerHTML = options;
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
  const keyword = $('noticeKeywordFilter').value.trim();
  const createdFrom = $('noticeFromFilter').value;
  const createdTo = $('noticeToFilter').value;
  const params = new URLSearchParams();
  if (keyword) params.set('keyword', keyword);
  if (createdFrom) params.set('createdFrom', createdFrom);
  if (createdTo) params.set('createdTo', createdTo);
  const query = params.toString();
  const rows = await request(`/api/notices${query ? `?${query}` : ''}`);
  renderList($('notices'), rows, '暂无通知');
  $('loginStatus').textContent = `通知查询完成：${rows.length} 条`;
}

async function clearNoticesFilter() {
  $('noticeKeywordFilter').value = '';
  $('noticeFromFilter').value = '';
  $('noticeToFilter').value = '';
  await refreshDashboard();
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

async function provinceAction(action) {
  const reports = await request('/api/reports');
  const enterprises = await request('/api/enterprises');
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
  $('saveNoticeBtn').addEventListener('click', saveNotice);
  $('reviewEnterpriseBtn').addEventListener('click', () => provinceAction('enterprise'));
  $('provinceApproveBtn').addEventListener('click', () => provinceAction('approve'));
  $('provinceCorrectBtn').addEventListener('click', () => provinceAction('correct'));
  $('publishMinistryBtn').addEventListener('click', publishMinistry);
  $('exportMinistryBtn').addEventListener('click', exportMinistryXml);
  $('previewMinistryBtn').addEventListener('click', previewMinistry);
  $('exportMinistryExcelBtn').addEventListener('click', exportMinistryExcel);
  $('exportMinistryJsonBtn').addEventListener('click', exportMinistryJson);
  $('searchNoticesBtn').addEventListener('click', searchNotices);
  $('clearNoticesBtn').addEventListener('click', clearNoticesFilter);
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
bindActions();
