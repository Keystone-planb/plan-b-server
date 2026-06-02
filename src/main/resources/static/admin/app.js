'use strict';

// ════════════════════════════════════════════════════════════════════════════
// 상태
// ════════════════════════════════════════════════════════════════════════════
let accessToken   = localStorage.getItem('admin_token')   || null;
let refreshToken  = localStorage.getItem('admin_refresh')  || null;

let allUsers  = [];
let allPlaces = [];

let currentUserId    = null;
let currentTripId    = null;
let currentTripTitle = null;

let currentTripPlaces    = [];   // 정렬 기준이 바뀌어도 원본 보존
let tripPlacesSortCol    = 'day';
let tripPlacesSortDir    = 'asc';

const tripPlaceJsonData = {};    // { tripPlaceId: { openingHours, reviewData } }
const pollingTimers     = {};    // { googlePlaceId: intervalId }

const SORT_COLS = ['day','visitOrder','name','visitTime','source','transportMode','placeType','rating'];

// ════════════════════════════════════════════════════════════════════════════
// 초기 진입
// ════════════════════════════════════════════════════════════════════════════
window.addEventListener('DOMContentLoaded', () => {
  if (accessToken) { showAdmin(); switchTab('users'); loadUsers(); }
});

// ════════════════════════════════════════════════════════════════════════════
// 로그인 / 로그아웃
// ════════════════════════════════════════════════════════════════════════════
async function handleLogin(e) {
  e.preventDefault();
  const btn = document.getElementById('login-btn');
  const err = document.getElementById('login-err');
  err.classList.add('hidden');
  btn.disabled = true;
  btn.textContent = '로그인 중...';

  try {
    const data = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        email:    document.getElementById('inp-email').value,
        password: document.getElementById('inp-pw').value
      })
    }).then(res => {
      if (!res.ok) return res.json().then(d => { throw new Error(d.message || '로그인 실패'); });
      return res.json();
    });

    accessToken  = data.access_token;
    refreshToken = data.refresh_token;
    localStorage.setItem('admin_token',   accessToken);
    localStorage.setItem('admin_refresh', refreshToken);
    localStorage.setItem('admin_nick',    data.nickname || '');

    document.getElementById('hdr-nickname').textContent = data.nickname || '';
    showAdmin();
    switchTab('users');
    loadUsers();
  } catch (ex) {
    err.textContent = ex.message;
    err.classList.remove('hidden');
  } finally {
    btn.disabled = false;
    btn.textContent = '로그인';
  }
}

async function handleLogout() {
  try {
    await apiFetch('/api/auth/logout', { method: 'POST', body: JSON.stringify({ refreshToken }) });
  } catch (_) {}

  Object.values(pollingTimers).forEach(clearInterval);
  accessToken = refreshToken = null;
  allUsers = []; allPlaces = []; currentTripPlaces = [];
  currentUserId = currentTripId = currentTripTitle = null;
  localStorage.removeItem('admin_token');
  localStorage.removeItem('admin_refresh');
  localStorage.removeItem('admin_nick');

  document.getElementById('admin-section').classList.add('hidden');
  document.getElementById('login-section').classList.remove('hidden');
  document.getElementById('inp-email').value = '';
  document.getElementById('inp-pw').value = '';
}

function showAdmin() {
  document.getElementById('login-section').classList.add('hidden');
  document.getElementById('admin-section').classList.remove('hidden');
  document.getElementById('hdr-nickname').textContent = localStorage.getItem('admin_nick') || '';
}

// ════════════════════════════════════════════════════════════════════════════
// 탭 전환
// ════════════════════════════════════════════════════════════════════════════
function switchTab(name) {
  ['users', 'places'].forEach(t => {
    document.getElementById(`tab-${t}`).classList.toggle('hidden', t !== name);
    const btn = document.getElementById(`tab-btn-${t}`);
    if (t === name) {
      btn.classList.add('text-indigo-600', 'border-indigo-600', 'font-semibold');
      btn.classList.remove('text-gray-400', 'border-transparent', 'font-medium');
    } else {
      btn.classList.remove('text-indigo-600', 'border-indigo-600', 'font-semibold');
      btn.classList.add('text-gray-400', 'border-transparent', 'font-medium');
    }
  });
  if (name === 'places' && allPlaces.length === 0) loadDbPlaces();
}

// ════════════════════════════════════════════════════════════════════════════
// ── 사용자 관리
// ════════════════════════════════════════════════════════════════════════════
async function loadUsers() {
  show('users-loading'); hide('users-table-wrap'); hide('users-empty');
  try {
    allUsers = await apiFetch('/api/admin/users');
    renderUsersTable(allUsers);
  } catch (ex) {
    document.getElementById('users-loading').textContent = '불러오기 실패: ' + ex.message;
  }
}

function applyUserFilter() {
  const q = (document.getElementById('user-search').value || '').toLowerCase();
  renderUsersTable(allUsers.filter(u =>
    (u.email || '').toLowerCase().includes(q) ||
    (u.nickname || '').toLowerCase().includes(q)
  ));
}

function renderUsersTable(users) {
  hide('users-loading');
  if (users.length === 0) { show('users-empty'); hide('users-table-wrap'); }
  else { hide('users-empty'); show('users-table-wrap'); }

  document.getElementById('users-tbody').innerHTML = users.map(u => `
    <tr class="hover:bg-gray-50 cursor-pointer transition"
        onclick="showUserTrips(${u.id}, '${escJs(u.nickname)}')">
      <td class="px-4 py-3 text-gray-400">${u.id}</td>
      <td class="px-4 py-3 font-medium">${escHtml(u.email)}</td>
      <td class="px-4 py-3">${escHtml(u.nickname)}</td>
      <td class="px-4 py-3">
        ${u.role === 'ADMIN'
          ? '<span class="bg-red-100 text-red-700 text-xs font-bold px-2 py-0.5 rounded-full">ADMIN</span>'
          : '<span class="bg-gray-100 text-gray-500 text-xs px-2 py-0.5 rounded-full">USER</span>'}
      </td>
      <td class="px-4 py-3 text-gray-500">${escHtml(u.provider)}</td>
      <td class="px-4 py-3">
        ${u.status === 'ACTIVE'
          ? '<span class="bg-green-100 text-green-700 text-xs px-2 py-0.5 rounded-full">활성</span>'
          : '<span class="bg-gray-200 text-gray-500 text-xs px-2 py-0.5 rounded-full">탈퇴</span>'}
      </td>
      <td class="px-4 py-3 text-gray-400">${fmtDate(u.createdAt)}</td>
      <td class="px-4 py-3" onclick="event.stopPropagation()">
        <button onclick="deleteUser(${u.id}, '${escJs(u.email)}')"
                class="bg-red-500 hover:bg-red-600 text-white text-xs px-3 py-1 rounded transition">
          삭제
        </button>
      </td>
    </tr>`).join('');

  document.getElementById('users-count').textContent =
    `총 ${users.length}명 (전체 ${allUsers.length}명)`;
}

async function deleteUser(userId, email) {
  if (!confirm(`⚠️ [사용자 삭제]\n\n이메일: ${email}\n\n해당 사용자의 모든 여행, 장소, 알림이 함께 삭제됩니다.\n정말 삭제하시겠습니까?`)) return;
  try {
    await apiFetch(`/api/admin/users/${userId}`, { method: 'DELETE' });
    allUsers = allUsers.filter(u => u.id !== userId);
    renderUsersTable(allUsers);
    if (currentUserId === userId) closeTripsPanel();
    alert('삭제되었습니다.');
  } catch (ex) { alert('삭제 실패: ' + ex.message); }
}

// ── 여행 패널 ──────────────────────────────────────────────────────────────
async function showUserTrips(userId, nickname) {
  currentUserId = userId;
  closeTripPlacesPanel();
  show('trips-panel');
  document.getElementById('trips-title').textContent = `${nickname}님의 여행`;
  show('trips-loading'); hide('trips-table-wrap'); hide('trips-empty');

  try {
    const trips = await apiFetch(`/api/admin/users/${userId}/trips`);
    renderTripsTable(trips, nickname);
  } catch (ex) {
    document.getElementById('trips-loading').textContent = '불러오기 실패: ' + ex.message;
  }
  document.getElementById('trips-panel').scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function renderTripsTable(trips, nickname) {
  hide('trips-loading');
  document.getElementById('trips-title').textContent = `${nickname}님의 여행 (${trips.length}개)`;
  if (trips.length === 0) { show('trips-empty'); hide('trips-table-wrap'); return; }
  hide('trips-empty'); show('trips-table-wrap');

  document.getElementById('trips-tbody').innerHTML = trips.map(t => `
    <tr class="hover:bg-gray-50 cursor-pointer transition"
        onclick="showTripPlaces(${t.tripId}, '${escJs(t.title)}')">
      <td class="px-4 py-3 text-gray-400">${t.tripId}</td>
      <td class="px-4 py-3 font-medium">${escHtml(t.title)}</td>
      <td class="px-4 py-3 text-gray-500">${t.startDate} ~ ${t.endDate}</td>
      <td class="px-4 py-3 text-gray-500">${t.transportMode || '—'}</td>
      <td class="px-4 py-3 text-gray-500">${escHtml(t.travelStyles || '—')}</td>
      <td class="px-4 py-3 text-gray-400">${fmtDate(t.createdAt)}</td>
      <td class="px-4 py-3" onclick="event.stopPropagation()">
        <button onclick="deleteTrip(${t.tripId}, '${escJs(t.title)}')"
                class="bg-red-500 hover:bg-red-600 text-white text-xs px-3 py-1 rounded transition">
          강제 삭제
        </button>
      </td>
    </tr>`).join('');
}

async function deleteTrip(tripId, title) {
  if (!confirm(`⚠️ [여행 강제 삭제]\n\n제목: ${title}\n\n해당 여행의 모든 일정과 장소가 삭제됩니다.\n정말 삭제하시겠습니까?`)) return;
  try {
    await apiFetch(`/api/admin/trips/${tripId}`, { method: 'DELETE' });
    if (currentTripId === tripId) closeTripPlacesPanel();
    if (currentUserId) {
      const nick = document.getElementById('trips-title').textContent.replace(/님의 여행.*/, '');
      await showUserTrips(currentUserId, nick);
    }
    alert('삭제되었습니다.');
  } catch (ex) { alert('삭제 실패: ' + ex.message); }
}

function closeTripsPanel() {
  currentUserId = null;
  hide('trips-panel');
  closeTripPlacesPanel();
}

// ── 여행 장소 패널 ─────────────────────────────────────────────────────────
async function showTripPlaces(tripId, title) {
  currentTripId    = tripId;
  currentTripTitle = title;
  currentTripPlaces = [];
  tripPlacesSortCol = 'day';
  tripPlacesSortDir = 'asc';

  show('trip-places-panel');
  document.getElementById('trip-places-title').textContent = `${title}의 장소`;
  show('trip-places-loading'); hide('trip-places-table-wrap'); hide('trip-places-empty');

  try {
    currentTripPlaces = await apiFetch(`/api/admin/trips/${tripId}/places`);
    renderSortedTripPlaces();
  } catch (ex) {
    document.getElementById('trip-places-loading').textContent = '불러오기 실패: ' + ex.message;
  }
  document.getElementById('trip-places-panel').scrollIntoView({ behavior: 'smooth', block: 'start' });
}

// ── 정렬 ───────────────────────────────────────────────────────────────────
function sortTripPlaces(col) {
  if (tripPlacesSortCol === col) {
    tripPlacesSortDir = tripPlacesSortDir === 'asc' ? 'desc' : 'asc';
  } else {
    tripPlacesSortCol = col;
    tripPlacesSortDir = 'asc';
  }
  renderSortedTripPlaces();
}

function renderSortedTripPlaces() {
  const sorted = [...currentTripPlaces].sort((a, b) => {
    const av = a[tripPlacesSortCol] ?? '';
    const bv = b[tripPlacesSortCol] ?? '';
    const cmp = (typeof av === 'number' && typeof bv === 'number')
      ? av - bv
      : String(av).localeCompare(String(bv), 'ko');
    return tripPlacesSortDir === 'asc' ? cmp : -cmp;
  });

  // 정렬 아이콘 업데이트
  SORT_COLS.forEach(col => {
    const el = document.getElementById(`si-${col}`);
    if (!el) return;
    if (col === tripPlacesSortCol) {
      el.textContent = tripPlacesSortDir === 'asc' ? '▲' : '▼';
      el.className   = 'text-indigo-500';
    } else {
      el.textContent = '⇅';
      el.className   = 'text-gray-300';
    }
  });

  renderTripPlacesTable(sorted, currentTripTitle);
}

// ── 여행 장소 테이블 렌더링 (전체 필드) ───────────────────────────────────
function renderTripPlacesTable(places, title) {
  hide('trip-places-loading');
  document.getElementById('trip-places-title').textContent =
    `${title}의 장소 (${places.length}개)`;

  if (places.length === 0) { show('trip-places-empty'); hide('trip-places-table-wrap'); return; }
  hide('trip-places-empty'); show('trip-places-table-wrap');

  document.getElementById('trip-places-tbody').innerHTML = places.map(p => {
    const sid = safe(p.googlePlaceId);

    // JSON 데이터는 전역 Map에 보관 (onclick에 직접 삽입하면 이스케이프 문제)
    tripPlaceJsonData[p.tripPlaceId] = {
      openingHours: p.openingHours,
      reviewData:   p.reviewData
    };

    const statusBadge = p.analysisStatus === 'COMPLETE'
      ? '<span class="bg-green-100 text-green-700 text-xs font-semibold px-2 py-0.5 rounded-full">완료</span>'
      : '<span class="bg-yellow-100 text-yellow-700 text-xs font-semibold px-2 py-0.5 rounded-full">미완료</span>';

    return `
    <tr id="tpr-${sid}" class="hover:bg-gray-50 transition">
      <!-- 일차 / 날짜 -->
      <td class="px-3 py-3 text-center whitespace-nowrap">
        <span class="font-bold text-indigo-500">${p.day}일차</span>
        <div class="text-gray-400 text-xs">${p.date}</div>
      </td>
      <!-- 방문 순서 -->
      <td class="px-3 py-3 text-center text-gray-600">${p.visitOrder}</td>
      <!-- 장소명 -->
      <td class="px-3 py-3 font-medium max-w-[140px]">
        <div class="truncate" title="${escHtml(p.name)}">${escHtml(p.name)}</div>
      </td>
      <!-- Google Place ID -->
      <td class="px-3 py-3 text-gray-400 text-xs max-w-[130px] truncate"
          title="${escHtml(p.googlePlaceId)}">${escHtml(p.googlePlaceId || '—')}</td>
      <!-- 방문시간 -->
      <td class="px-3 py-3 text-gray-600 whitespace-nowrap">${p.visitTime || '—'}</td>
      <!-- 종료시간 -->
      <td class="px-3 py-3 text-gray-600 whitespace-nowrap">${p.endTime || '—'}</td>
      <!-- 메모 -->
      <td class="px-3 py-3 text-gray-500 text-xs max-w-[120px]">
        <div class="truncate" title="${escHtml(p.memo)}">${escHtml(p.memo || '—')}</div>
      </td>
      <!-- 출처 -->
      <td class="px-3 py-3">${sourceColor(p.source)}</td>
      <!-- 이동수단 -->
      <td class="px-3 py-3">
        ${p.transportMode
          ? `<span class="bg-blue-100 text-blue-700 text-xs px-2 py-0.5 rounded-full">${p.transportMode}</span>`
          : '<span class="text-gray-300 text-xs">—</span>'}
      </td>
      <!-- 위도/경도 -->
      <td class="px-3 py-3 text-xs text-gray-500 whitespace-nowrap">
        ${p.latitude != null
          ? `${p.latitude.toFixed(5)}<br>${p.longitude.toFixed(5)}`
          : '—'}
      </td>
      <!-- 카테고리 -->
      <td class="px-3 py-3 text-xs text-gray-500 max-w-[100px]">
        <div class="truncate" title="${escHtml(p.category)}">${escHtml(p.category || '—')}</div>
      </td>
      <!-- 타입 / 스페이스 / 분위기 -->
      <td class="px-3 py-3">
        <div class="flex flex-col gap-1">
          ${p.placeType ? `<span class="bg-indigo-100 text-indigo-700 text-xs px-2 py-0.5 rounded-full">${p.placeType}</span>` : ''}
          ${p.space     ? `<span class="bg-purple-100 text-purple-700 text-xs px-2 py-0.5 rounded-full">${p.space}</span>`    : ''}
          ${p.mood      ? `<span class="bg-pink-100 text-pink-700 text-xs px-2 py-0.5 rounded-full">${p.mood}</span>`         : ''}
          ${!p.placeType && !p.space && !p.mood ? '<span class="text-gray-300 text-xs">—</span>' : ''}
        </div>
      </td>
      <!-- 평점 -->
      <td class="px-3 py-3 whitespace-nowrap text-xs">
        ${p.rating != null
          ? `⭐ ${p.rating.toFixed(1)}<div class="text-gray-400">(${p.userRatingsTotal ?? 0})</div>`
          : '—'}
      </td>
      <!-- 영업시간 (JSONB) -->
      <td class="px-3 py-3">
        ${p.openingHours
          ? `<button onclick="showJsonDetail(${p.tripPlaceId}, 'openingHours', '영업시간')"
                     class="text-xs text-blue-500 hover:text-blue-700 underline">보기</button>`
          : '<span class="text-gray-300 text-xs">—</span>'}
      </td>
      <!-- 리뷰 데이터 (JSONB) -->
      <td class="px-3 py-3">
        ${p.reviewData
          ? `<button onclick="showJsonDetail(${p.tripPlaceId}, 'reviewData', '리뷰 데이터')"
                     class="text-xs text-blue-500 hover:text-blue-700 underline">보기</button>`
          : '<span class="text-gray-300 text-xs">—</span>'}
      </td>
      <!-- 분석상태 -->
      <td class="px-3 py-3" id="tpr-status-${sid}">${statusBadge}</td>
      <!-- 액션 -->
      <td class="px-3 py-3">
        <div class="flex flex-col gap-1">
          ${p.googlePlaceId
            ? `<button id="tpr-btn-${sid}"
                       onclick="triggerReanalyze('${escJs(p.googlePlaceId)}', '${escJs(p.name)}', 'tpr')"
                       class="bg-amber-500 hover:bg-amber-600 text-white text-xs px-2 py-1 rounded transition whitespace-nowrap">
                 재분석
               </button>`
            : ''}
          <button onclick="deleteTripPlace(${p.tripPlaceId}, '${escJs(p.name)}')"
                  class="bg-red-500 hover:bg-red-600 text-white text-xs px-2 py-1 rounded transition whitespace-nowrap">
            장소 삭제
          </button>
        </div>
      </td>
    </tr>`;
  }).join('');
}

// ── JSON 데이터 팝업 (영업시간 / 리뷰 데이터) ─────────────────────────────
function showJsonDetail(tripPlaceId, field, label) {
  const raw = tripPlaceJsonData[tripPlaceId]?.[field];
  if (!raw) { alert(`[${label}]\n\n데이터 없음`); return; }
  try {
    alert(`[${label}]\n\n${JSON.stringify(JSON.parse(raw), null, 2)}`);
  } catch {
    alert(`[${label}]\n\n${raw}`);
  }
}

// ── TripPlace 단건 삭제 ────────────────────────────────────────────────────
async function deleteTripPlace(tripPlaceId, name) {
  if (!confirm(`⚠️ [장소 삭제]\n\n장소명: ${name}\n\n이 장소와 연결된 메모가 함께 삭제됩니다.\n정말 삭제하시겠습니까?`)) return;
  try {
    await apiFetch(`/api/admin/trip-places/${tripPlaceId}`, { method: 'DELETE' });
    if (currentTripId && currentTripTitle) await showTripPlaces(currentTripId, currentTripTitle);
  } catch (ex) { alert('삭제 실패: ' + ex.message); }
}

function closeTripPlacesPanel() {
  currentTripId    = null;
  currentTripTitle = null;
  currentTripPlaces = [];
  hide('trip-places-panel');
}

// ════════════════════════════════════════════════════════════════════════════
// ── 장소 관리 탭 (DB Places)
// ════════════════════════════════════════════════════════════════════════════
async function loadDbPlaces() {
  show('p-loading'); hide('p-table-wrap'); hide('p-empty');
  try {
    allPlaces = await apiFetch('/api/admin/places');
    updatePlaceStats(allPlaces);
    applyPlaceFilter();
  } catch (ex) {
    document.getElementById('p-loading').textContent = '불러오기 실패: ' + ex.message;
  }
}

function applyPlaceFilter() {
  const q      = (document.getElementById('p-search').value || '').toLowerCase();
  const status = document.getElementById('p-filter-status').value;
  const type   = document.getElementById('p-filter-type').value;
  const f = allPlaces.filter(p =>
    (!q      || (p.name || '').toLowerCase().includes(q)) &&
    (!status || p.analysisStatus === status) &&
    (!type   || p.type === type)
  );
  renderDbPlacesTable(f);
  document.getElementById('p-count').textContent = `총 ${f.length}개 (전체 ${allPlaces.length}개)`;
}

function updatePlaceStats(places) {
  const complete = places.filter(p => p.analysisStatus === 'COMPLETE').length;
  document.getElementById('p-stat-total').textContent    = places.length;
  document.getElementById('p-stat-complete').textContent = complete;
  document.getElementById('p-stat-pending').textContent  = places.length - complete;
}

function renderDbPlacesTable(places) {
  hide('p-loading');
  if (places.length === 0) { show('p-empty'); hide('p-table-wrap'); return; }
  hide('p-empty'); show('p-table-wrap');

  document.getElementById('p-tbody').innerHTML = places.map(p => {
    const sid = safe(p.googlePlaceId);
    const statusBadge = p.analysisStatus === 'COMPLETE'
      ? `<span class="bg-green-100 text-green-700 text-xs font-semibold px-2 py-0.5 rounded-full">완료</span>`
      : `<span id="p-badge-${sid}" class="bg-yellow-100 text-yellow-700 text-xs font-semibold px-2 py-0.5 rounded-full">미완료</span>`;

    return `
    <tr class="hover:bg-gray-50 transition">
      <td class="px-4 py-3 text-gray-400">${p.id ?? '—'}</td>
      <td class="px-4 py-3 font-medium max-w-[160px]">
        <div class="truncate" title="${escHtml(p.name)}">${escHtml(p.name)}</div>
        <div class="text-gray-400 text-xs truncate mt-0.5" title="${escHtml(p.googlePlaceId)}">${escHtml(p.googlePlaceId || '')}</div>
      </td>
      <td class="px-4 py-3 text-gray-500 text-xs max-w-[180px] truncate" title="${escHtml(p.address)}">${escHtml(p.address || '—')}</td>
      <td class="px-4 py-3">${p.type  ? `<span class="bg-indigo-100 text-indigo-700 text-xs px-2 py-0.5 rounded-full">${p.type}</span>`  : '<span class="text-gray-300">—</span>'}</td>
      <td class="px-4 py-3">${p.space ? `<span class="bg-purple-100 text-purple-700 text-xs px-2 py-0.5 rounded-full">${p.space}</span>` : '<span class="text-gray-300">—</span>'}</td>
      <td class="px-4 py-3">${p.mood  ? `<span class="bg-pink-100 text-pink-700 text-xs px-2 py-0.5 rounded-full">${p.mood}</span>`   : '<span class="text-gray-300">—</span>'}</td>
      <td class="px-4 py-3">${p.rating != null ? `⭐ ${p.rating.toFixed(1)}` : '—'}</td>
      <td class="px-4 py-3 text-gray-400 text-xs">${fmtDate(p.lastSyncedAt)}</td>
      <td class="px-4 py-3" id="p-status-${sid}">${statusBadge}</td>
      <td class="px-4 py-3">
        ${p.googlePlaceId
          ? `<button id="p-btn-${sid}"
                     onclick="triggerReanalyze('${escJs(p.googlePlaceId)}', '${escJs(p.name)}', 'p')"
                     class="bg-amber-500 hover:bg-amber-600 disabled:opacity-50 disabled:cursor-not-allowed text-white text-xs px-3 py-1 rounded transition">
               재분석
             </button>`
          : '<span class="text-gray-300 text-xs">—</span>'}
      </td>
    </tr>`;
  }).join('');
}

// ════════════════════════════════════════════════════════════════════════════
// 재분석 + 폴링
// ════════════════════════════════════════════════════════════════════════════
async function triggerReanalyze(googlePlaceId, name, prefix) {
  const sid = safe(googlePlaceId);
  const btn        = document.getElementById(`${prefix}-btn-${sid}`);
  const statusCell = document.getElementById(`${prefix}-status-${sid}`);

  if (pollingTimers[googlePlaceId]) return;
  if (btn) { btn.disabled = true; btn.textContent = '요청 중...'; }

  try {
    await apiFetch(`/api/places/${encodeURIComponent(googlePlaceId)}/reanalyze`, { method: 'POST' });

    if (statusCell) statusCell.innerHTML =
      `<span id="${prefix}-badge-${sid}" class="bg-blue-100 text-blue-700 text-xs font-semibold px-2 py-0.5 rounded-full">분석 중...</span>`;
    if (btn) btn.textContent = '분석 중...';

    pollingTimers[googlePlaceId] = setInterval(
      () => pollAnalysisStatus(googlePlaceId, prefix), 3000
    );
  } catch (ex) {
    alert(`재분석 요청 실패: ${ex.message}`);
    if (btn) { btn.disabled = false; btn.textContent = '재분석'; }
  }
}

async function pollAnalysisStatus(googlePlaceId, prefix) {
  const sid = safe(googlePlaceId);
  try {
    const data = await apiFetch(`/api/places/${encodeURIComponent(googlePlaceId)}/analysis-status`);
    if (data.status === 'COMPLETE') {
      clearInterval(pollingTimers[googlePlaceId]);
      delete pollingTimers[googlePlaceId];

      const statusCell = document.getElementById(`${prefix}-status-${sid}`);
      const btn        = document.getElementById(`${prefix}-btn-${sid}`);
      if (statusCell) statusCell.innerHTML =
        `<span class="bg-green-100 text-green-700 text-xs font-semibold px-2 py-0.5 rounded-full">완료</span>`;
      if (btn) { btn.disabled = false; btn.textContent = '재분석'; }

      const pl = allPlaces.find(x => x.googlePlaceId === googlePlaceId);
      if (pl) { pl.analysisStatus = 'COMPLETE'; updatePlaceStats(allPlaces); }
    }
  } catch (_) {}
}

// ════════════════════════════════════════════════════════════════════════════
// 공통 Fetch
// ════════════════════════════════════════════════════════════════════════════
async function apiFetch(url, opts = {}) {
  const res = await fetch(url, {
    ...opts,
    headers: {
      'Content-Type': 'application/json',
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
      ...(opts.headers || {})
    }
  });

  if (res.status === 401) { handleLogout(); throw new Error('세션이 만료되었습니다. 다시 로그인해 주세요.'); }
  if (res.status === 403) { handleLogout(); throw new Error('관리자 권한이 없습니다.'); }
  if (!res.ok) {
    const t = await res.text().catch(() => '');
    throw new Error(t || `HTTP ${res.status}`);
  }
  const t = await res.text();
  if (!t) return null;
  try { return JSON.parse(t); } catch { return t; }  // 순수 문자열 응답(재분석 등) 안전 처리
}

// ════════════════════════════════════════════════════════════════════════════
// 유틸
// ════════════════════════════════════════════════════════════════════════════
function show(id) { document.getElementById(id)?.classList.remove('hidden'); }
function hide(id) { document.getElementById(id)?.classList.add('hidden'); }

function fmtDate(str) {
  if (!str) return '—';
  return str.replace('T', ' ').slice(0, 16);
}

function sourceColor(src) {
  const map = {
    NORMAL:  'bg-gray-100 text-gray-500',
    SOS:     'bg-red-100 text-red-600',
    WEATHER: 'bg-sky-100 text-sky-600',
    GAP:     'bg-teal-100 text-teal-600'
  };
  const cls = map[src] || 'bg-gray-100 text-gray-400';
  return `<span class="${cls} text-xs px-2 py-0.5 rounded-full">${src || 'NORMAL'}</span>`;
}

function escHtml(s) {
  if (s == null) return '';
  return String(s)
    .replace(/&/g,'&amp;').replace(/</g,'&lt;')
    .replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}
function escJs(s) {
  if (s == null) return '';
  return String(s).replace(/\\/g,'\\\\').replace(/'/g,"\\'");
}
function safe(s) {
  if (s == null) return '';
  return String(s).replace(/[^a-zA-Z0-9_-]/g,'_');
}
