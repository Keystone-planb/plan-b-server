/**
 * Plan B Admin — app.js
 * 순수 Fetch API + Tailwind CSS 기반 어드민 페이지 로직
 */

'use strict';

// ── 상태 ──────────────────────────────────────────────────────────────────
let accessToken = sessionStorage.getItem('admin_token') || null;
let refreshToken = sessionStorage.getItem('admin_refresh') || null;
let allPlaces = [];                     // 서버에서 받아온 전체 장소
const pollingTimers = {};               // { googlePlaceId: intervalId }

// ── 초기 진입 ─────────────────────────────────────────────────────────────
window.addEventListener('DOMContentLoaded', () => {
  if (accessToken) {
    showAdmin();
    loadPlaces();
  }
});

// ── 로그인 ────────────────────────────────────────────────────────────────
async function handleLogin(e) {
  e.preventDefault();
  const btn = document.getElementById('login-btn');
  const errEl = document.getElementById('login-error');
  errEl.classList.add('hidden');
  btn.disabled = true;
  btn.textContent = '로그인 중...';

  try {
    const res = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        email: document.getElementById('input-email').value,
        password: document.getElementById('input-password').value
      })
    });

    if (!res.ok) {
      const data = await res.json().catch(() => ({}));
      throw new Error(data.message || '이메일 또는 비밀번호를 확인해 주세요.');
    }

    const data = await res.json();
    accessToken = data.access_token;
    refreshToken = data.refresh_token;
    sessionStorage.setItem('admin_token', accessToken);
    sessionStorage.setItem('admin_refresh', refreshToken);

    document.getElementById('admin-nickname').textContent = data.nickname || data.user_id;
    showAdmin();
    loadPlaces();

  } catch (err) {
    errEl.textContent = err.message;
    errEl.classList.remove('hidden');
  } finally {
    btn.disabled = false;
    btn.textContent = '로그인';
  }
}

// ── 로그아웃 ──────────────────────────────────────────────────────────────
async function handleLogout() {
  try {
    await apiFetch('/api/auth/logout', {
      method: 'POST',
      body: JSON.stringify({ refreshToken })
    });
  } catch (_) { /* 무시 */ }

  // 모든 폴링 중지
  Object.values(pollingTimers).forEach(clearInterval);

  accessToken = null;
  refreshToken = null;
  sessionStorage.removeItem('admin_token');
  sessionStorage.removeItem('admin_refresh');
  allPlaces = [];

  document.getElementById('admin-section').classList.add('hidden');
  document.getElementById('login-section').classList.remove('hidden');
  document.getElementById('input-email').value = '';
  document.getElementById('input-password').value = '';
}

// ── 화면 전환 ─────────────────────────────────────────────────────────────
function showAdmin() {
  document.getElementById('login-section').classList.add('hidden');
  document.getElementById('admin-section').classList.remove('hidden');
  const stored = sessionStorage.getItem('admin_nickname');
  if (stored) document.getElementById('admin-nickname').textContent = stored;
}

// ── 장소 목록 로드 ────────────────────────────────────────────────────────
async function loadPlaces() {
  document.getElementById('table-loading').classList.remove('hidden');
  document.getElementById('places-table').classList.add('hidden');
  document.getElementById('no-results').classList.add('hidden');

  try {
    const data = await apiFetch('/api/admin/places');
    allPlaces = data;
    updateStats(data);
    applyFilter();
  } catch (err) {
    document.getElementById('table-loading').textContent = '불러오기 실패: ' + err.message;
  }
}

// ── 통계 카드 업데이트 ────────────────────────────────────────────────────
function updateStats(places) {
  const total = places.length;
  const complete = places.filter(p => p.analysisStatus === 'COMPLETE').length;
  document.getElementById('stat-total').textContent = total;
  document.getElementById('stat-complete').textContent = complete;
  document.getElementById('stat-pending').textContent = total - complete;
}

// ── 검색 / 필터 적용 ──────────────────────────────────────────────────────
function applyFilter() {
  const query  = (document.getElementById('search-input').value || '').toLowerCase();
  const status = document.getElementById('filter-status').value;
  const type   = document.getElementById('filter-type').value;

  const filtered = allPlaces.filter(p => {
    const nameMatch   = !query  || (p.name || '').toLowerCase().includes(query);
    const statusMatch = !status || p.analysisStatus === status;
    const typeMatch   = !type   || p.type === type;
    return nameMatch && statusMatch && typeMatch;
  });

  renderTable(filtered);
  document.getElementById('result-count').textContent =
      `총 ${filtered.length}개 (전체 ${allPlaces.length}개 중)`;
}

// ── 테이블 렌더링 ─────────────────────────────────────────────────────────
function renderTable(places) {
  document.getElementById('table-loading').classList.add('hidden');

  if (places.length === 0) {
    document.getElementById('places-table').classList.add('hidden');
    document.getElementById('no-results').classList.remove('hidden');
    return;
  }

  document.getElementById('no-results').classList.add('hidden');
  document.getElementById('places-table').classList.remove('hidden');

  const tbody = document.getElementById('places-tbody');
  tbody.innerHTML = places.map(p => buildRow(p)).join('');
}

function buildRow(p) {
  const statusBadge = p.analysisStatus === 'COMPLETE'
    ? `<span class="bg-green-100 text-green-800 text-xs font-semibold px-2 py-0.5 rounded-full">완료</span>`
    : `<span id="badge-${safe(p.googlePlaceId)}" class="bg-yellow-100 text-yellow-800 text-xs font-semibold px-2 py-0.5 rounded-full">미완료</span>`;

  const lastSync = p.lastSyncedAt
    ? p.lastSyncedAt.replace('T', ' ').slice(0, 16)
    : '—';

  const reanalyzeBtn = p.googlePlaceId
    ? `<button id="btn-${safe(p.googlePlaceId)}"
              onclick="triggerReanalyze('${escJs(p.googlePlaceId)}', '${escJs(p.name)}')"
              class="bg-amber-500 hover:bg-amber-600 disabled:opacity-50 disabled:cursor-not-allowed text-white text-xs font-medium px-3 py-1 rounded transition">
         재분석
       </button>`
    : '<span class="text-gray-300 text-xs">—</span>';

  return `
    <tr id="row-${safe(p.googlePlaceId)}" class="hover:bg-gray-50 transition">
      <td class="px-4 py-3 text-gray-400 text-xs">${p.id ?? '—'}</td>
      <td class="px-4 py-3 font-medium max-w-[180px]">
        <div class="truncate" title="${escHtml(p.name)}">${escHtml(p.name)}</div>
        <div class="text-gray-400 text-xs truncate mt-0.5" title="${escHtml(p.googlePlaceId)}">${escHtml(p.googlePlaceId || '')}</div>
      </td>
      <td class="px-4 py-3 text-gray-500 text-xs max-w-[200px]">
        <div class="truncate" title="${escHtml(p.address)}">${escHtml(p.address || '—')}</div>
      </td>
      <td class="px-4 py-3">${p.type  ? `<span class="bg-indigo-100 text-indigo-700 text-xs px-2 py-0.5 rounded-full">${p.type}</span>`  : '<span class="text-gray-300">—</span>'}</td>
      <td class="px-4 py-3">${p.space ? `<span class="bg-purple-100 text-purple-700 text-xs px-2 py-0.5 rounded-full">${p.space}</span>` : '<span class="text-gray-300">—</span>'}</td>
      <td class="px-4 py-3">${p.mood  ? `<span class="bg-pink-100 text-pink-700 text-xs px-2 py-0.5 rounded-full">${p.mood}</span>`   : '<span class="text-gray-300">—</span>'}</td>
      <td class="px-4 py-3">${p.rating != null ? `⭐ ${p.rating.toFixed(1)} <span class="text-gray-400 text-xs">(${p.userRatingsTotal ?? 0})</span>` : '—'}</td>
      <td class="px-4 py-3 text-gray-500 text-xs">${lastSync}</td>
      <td class="px-4 py-3" id="status-cell-${safe(p.googlePlaceId)}">${statusBadge}</td>
      <td class="px-4 py-3">${reanalyzeBtn}</td>
    </tr>`;
}

// ── 재분석 트리거 + 폴링 ──────────────────────────────────────────────────
async function triggerReanalyze(googlePlaceId, name) {
  const safeId = safe(googlePlaceId);
  const btn    = document.getElementById(`btn-${safeId}`);
  const cell   = document.getElementById(`status-cell-${safeId}`);

  if (!btn || !cell) return;

  // 이미 폴링 중이면 무시
  if (pollingTimers[googlePlaceId]) return;

  btn.disabled = true;
  btn.textContent = '요청 중...';

  try {
    await apiFetch(`/api/places/${encodeURIComponent(googlePlaceId)}/reanalyze`, {
      method: 'POST'
    });

    cell.innerHTML = `<span id="badge-${safeId}" class="bg-blue-100 text-blue-800 text-xs font-semibold px-2 py-0.5 rounded-full">분석 중...</span>`;
    btn.textContent = '분석 중...';

    // 로컬 상태도 PENDING 으로 업데이트
    const place = allPlaces.find(p => p.googlePlaceId === googlePlaceId);
    if (place) place.analysisStatus = 'PENDING';

    // 3초 간격 폴링 시작
    pollingTimers[googlePlaceId] = setInterval(() => pollStatus(googlePlaceId), 3000);

  } catch (err) {
    alert(`재분석 요청 실패: ${err.message}`);
    btn.disabled = false;
    btn.textContent = '재분석';
  }
}

async function pollStatus(googlePlaceId) {
  const safeId = safe(googlePlaceId);

  try {
    const data = await apiFetch(`/api/places/${encodeURIComponent(googlePlaceId)}/analysis-status`);

    if (data.status === 'COMPLETE') {
      // 폴링 중단
      clearInterval(pollingTimers[googlePlaceId]);
      delete pollingTimers[googlePlaceId];

      // 배지 + 버튼 업데이트
      const cell = document.getElementById(`status-cell-${safeId}`);
      const btn  = document.getElementById(`btn-${safeId}`);
      if (cell) cell.innerHTML = `<span class="bg-green-100 text-green-800 text-xs font-semibold px-2 py-0.5 rounded-full">완료</span>`;
      if (btn)  { btn.disabled = false; btn.textContent = '재분석'; }

      // 로컬 상태 업데이트 후 통계 갱신
      const place = allPlaces.find(p => p.googlePlaceId === googlePlaceId);
      if (place) place.analysisStatus = 'COMPLETE';
      updateStats(allPlaces);
    }
  } catch (_) {
    // 폴링 실패는 조용히 무시 (다음 주기에 재시도)
  }
}

// ── 공통 Fetch 유틸 ───────────────────────────────────────────────────────
async function apiFetch(url, options = {}) {
  const headers = {
    'Content-Type': 'application/json',
    ...(accessToken ? { 'Authorization': `Bearer ${accessToken}` } : {}),
    ...(options.headers || {})
  };

  const res = await fetch(url, { ...options, headers });

  if (res.status === 401) {
    handleLogout();
    throw new Error('세션이 만료되었습니다. 다시 로그인해 주세요.');
  }

  if (res.status === 403) {
    handleLogout();
    throw new Error('관리자 권한이 없습니다.');
  }

  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(text || `HTTP ${res.status}`);
  }

  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

// ── 문자열 이스케이프 유틸 ────────────────────────────────────────────────
function escHtml(str) {
  if (str == null) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function escJs(str) {
  if (str == null) return '';
  return String(str).replace(/\\/g, '\\\\').replace(/'/g, "\\'");
}

// element id 에서 특수문자 제거
function safe(str) {
  if (str == null) return '';
  return String(str).replace(/[^a-zA-Z0-9_-]/g, '_');
}
