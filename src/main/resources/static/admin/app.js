'use strict';

// ════════════════════════════════════════════════════════════════════════════
// 상태
// ════════════════════════════════════════════════════════════════════════════
let accessToken   = localStorage.getItem('admin_token')   || null;
let refreshToken  = localStorage.getItem('admin_refresh')  || null;

let allUsers         = [];
let allPlaces        = [];
let allNotifications = [];

let currentUserId    = null;
let currentTripId    = null;
let currentTripTitle = null;

let currentTripPlaces    = [];   // 정렬 기준이 바뀌어도 원본 보존
let tripPlacesSortCol    = 'day';
let tripPlacesSortDir    = 'asc';

const tripPlaceJsonData = {};    // { tripPlaceId: { openingHours, reviewData, category, memo } }
const placeJsonData     = {};    // { googlePlaceId_safe: { openingHours, reviewData, address, category, reviewSummary } }
const pollingTimers     = {};    // { googlePlaceId: intervalId }

// 여행 장소 정렬 컬럼 목록
const SORT_COLS = ['tripPlaceId','day','visitOrder','name','visitTime','source','transportMode','placeType','rating'];

// 장소 관리 탭 정렬 상태
let placesSortCol = 'id';
let placesSortDir = 'asc';
const PLACE_SORT_COLS = ['id','name','type','space','mood','rating','userRatingsTotal','priceLevel','businessStatus','lastSyncedAt','analysisStatus'];

// ════════════════════════════════════════════════════════════════════════════
// 초기 진입
// ════════════════════════════════════════════════════════════════════════════
window.addEventListener('DOMContentLoaded', () => {
  initEnvBadge();
  if (accessToken) { showAdmin(); switchTab('users'); loadUsers(); }
});

// ════════════════════════════════════════════════════════════════════════════
// 텍스트 전체 보기 모달
// ════════════════════════════════════════════════════════════════════════════
function showTextModal(title, content) {
  if (!content) return;
  document.getElementById('text-modal-title').textContent = title;
  document.getElementById('text-modal-body').textContent  = content;
  document.getElementById('text-modal').classList.remove('hidden');
}
function closeTextModal() {
  document.getElementById('text-modal').classList.add('hidden');
}
// ESC 키로 모달 닫기
window.addEventListener('keydown', e => {
  if (e.key === 'Escape') closeTextModal();
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
    }).then(async res => {
      if (!res.ok) {
        let msg = `로그인 실패 (${res.status})`;
        try { const d = await res.json(); msg = d.error || d.message || msg; } catch (_) {}
        throw new Error(msg);
      }
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
    await fetch('/api/auth/logout', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {})
      },
      body: JSON.stringify({ refreshToken })
    });
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
  loadStats();
  loadServerStatus();
}

// 서버 상태 확인 — /actuator/health (인증 불필요, 60초마다 자동 갱신)
let serverStatusTimer = null;
async function loadServerStatus() {
  const dot  = document.getElementById('server-status-dot');
  const text = document.getElementById('server-status-text');
  try {
    const res = await fetch('/actuator/health');
    const data = await res.json().catch(() => ({}));
    if (res.ok && data.status === 'UP') {
      dot.className  = 'w-3 h-3 rounded-full bg-green-400 flex-shrink-0';
      text.textContent = '정상';
      text.className = 'text-base font-bold text-green-600';
    } else {
      dot.className  = 'w-3 h-3 rounded-full bg-red-500 flex-shrink-0';
      text.textContent = '이상 감지';
      text.className = 'text-base font-bold text-red-600';
    }
  } catch (_) {
    dot.className  = 'w-3 h-3 rounded-full bg-red-500 flex-shrink-0';
    text.textContent = '응답 없음';
    text.className = 'text-base font-bold text-red-600';
  }

  // 기존 타이머 초기화 후 60초마다 재확인
  clearTimeout(serverStatusTimer);
  serverStatusTimer = setTimeout(loadServerStatus, 60_000);
}

async function triggerWeatherScheduler() {
  const btn = document.getElementById('weather-btn');
  if (!btn || btn.disabled) return;

  if (!confirm('날씨 스케줄러를 지금 바로 실행할까요?\n24시간 이내 일정을 대상으로 날씨 알림이 즉시 발송됩니다.')) return;

  btn.disabled = true;
  btn.textContent = '⏳ 실행 중...';

  try {
    await apiFetch('/api/admin/scheduler/weather', { method: 'POST' });
    btn.textContent = '✅ 실행 완료';
    loadStats();  // 알림 수 갱신
    setTimeout(() => { btn.textContent = '🌧 날씨 스케줄러 실행'; btn.disabled = false; }, 3000);
  } catch (ex) {
    btn.textContent = '❌ 실행 실패';
    alert('스케줄러 실행 실패: ' + ex.message);
    setTimeout(() => { btn.textContent = '🌧 날씨 스케줄러 실행'; btn.disabled = false; }, 3000);
  }
}

async function loadStats() {
  try {
    const s = await apiFetch('/api/admin/stats');
    document.getElementById('stat-users').textContent         = s.totalUsers.toLocaleString() + '명';
    document.getElementById('stat-trips').textContent         = s.totalTrips.toLocaleString() + '개';
    document.getElementById('stat-places').textContent        = s.analyzedPlaces.toLocaleString() + ' / ' + s.totalPlaces.toLocaleString();
    document.getElementById('stat-notifications').textContent = s.unsentNotifications.toLocaleString() + ' / ' + s.totalNotifications.toLocaleString();
  } catch (_) {
    // 통계 로드 실패 시 기존 기능에 영향 없이 조용히 무시
  }
}

// ════════════════════════════════════════════════════════════════════════════
// 탭 전환
// ════════════════════════════════════════════════════════════════════════════
function switchTab(name) {
  ['users', 'places', 'notifications'].forEach(t => {
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
  if (name === 'places'        && allPlaces.length        === 0) loadDbPlaces();
  if (name === 'notifications' && allNotifications.length  === 0) loadNotifications();
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
      <!-- 여행 선호 스타일 배지 (클릭 시 로드) -->
      <td class="px-4 py-3" id="mood-cell-${u.id}" onclick="event.stopPropagation()">
        <button onclick="loadUserMoodBadges(${u.id})"
                class="text-xs text-indigo-400 hover:text-indigo-600 underline whitespace-nowrap">
          보기
        </button>
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

// ── 리뷰 요약 추출 헬퍼 ────────────────────────────────────────────────────
function extractReviewSummary(reviewDataStr) {
  if (!reviewDataStr) return null;
  try {
    const d = JSON.parse(reviewDataStr);
    return d.totalSummary ?? d.reviewSummary ?? d.summary ?? d.googleReview ?? d.naverReview ?? null;
  } catch {
    return null;
  }
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

    // JSON + 텍스트 데이터 전역 Map에 보관 (onclick 문자열에 직접 삽입하면 이스케이프 문제)
    tripPlaceJsonData[p.tripPlaceId] = {
      openingHours: p.openingHours,
      reviewData:   p.reviewData,
      category:     p.category,
      memo:         p.memo
    };

    const statusBadge = p.analysisStatus === 'COMPLETE'
      ? '<span class="bg-green-100 text-green-700 text-xs font-semibold px-2 py-0.5 rounded-full">완료</span>'
      : '<span class="bg-yellow-100 text-yellow-700 text-xs font-semibold px-2 py-0.5 rounded-full">미완료</span>';

    const reviewSummary = extractReviewSummary(p.reviewData);

    return `
    <tr id="tpr-${sid}" class="hover:bg-gray-50 transition">
      <td class="px-3 py-3 text-gray-500 font-mono text-xs whitespace-nowrap">${p.tripPlaceId ?? '—'}</td>
      <td class="px-3 py-3 text-center whitespace-nowrap">
        <span class="font-bold text-indigo-500">${p.day}일차</span>
      </td>
      <td class="px-3 py-3 text-gray-400 text-xs whitespace-nowrap">${p.date ?? '—'}</td>
      <td class="px-3 py-3 text-center text-gray-600">${p.visitOrder}</td>
      <td class="px-3 py-3 font-medium max-w-[140px]">
        <div class="truncate" title="${escHtml(p.name)}">${escHtml(p.name)}</div>
      </td>
      <td class="px-3 py-3 text-gray-400 text-xs max-w-[130px] truncate"
          title="${escHtml(p.googlePlaceId)}">${escHtml(p.googlePlaceId || '—')}</td>
      <td class="px-3 py-3 text-gray-600 whitespace-nowrap">${p.visitTime || '—'}</td>
      <td class="px-3 py-3 text-gray-600 whitespace-nowrap">${p.endTime || '—'}</td>
      <td class="px-3 py-3">
        ${p.transportMode
          ? `<span class="bg-blue-100 text-blue-700 text-xs px-2 py-0.5 rounded-full">${p.transportMode}</span>`
          : '<span class="text-gray-300 text-xs">—</span>'}
      </td>
      <td class="px-3 py-3 text-gray-300 text-xs text-center">—</td>
      <td class="px-3 py-3 text-xs text-gray-500 whitespace-nowrap font-mono">
        ${p.latitude != null ? p.latitude.toFixed(6) : '—'}
      </td>
      <td class="px-3 py-3 text-xs text-gray-500 whitespace-nowrap font-mono">
        ${p.longitude != null ? p.longitude.toFixed(6) : '—'}
      </td>
      <!-- 카테고리 (클릭 → 모달) -->
      <td class="px-3 py-3 text-xs text-gray-500 max-w-[100px] expandable"
          onclick="showTripPlaceText(${p.tripPlaceId}, 'category', '카테고리')">
        <div class="truncate">${escHtml(p.category || '—')}</div>
      </td>
      <td class="px-3 py-3">
        ${p.placeType
          ? `<span class="bg-indigo-100 text-indigo-700 text-xs px-2 py-0.5 rounded-full">${p.placeType}</span>`
          : '<span class="text-gray-300 text-xs">—</span>'}
      </td>
      <td class="px-3 py-3">
        ${p.space
          ? `<span class="bg-purple-100 text-purple-700 text-xs px-2 py-0.5 rounded-full">${p.space}</span>`
          : '<span class="text-gray-300 text-xs">—</span>'}
      </td>
      <td class="px-3 py-3">
        ${p.mood
          ? `<span class="bg-pink-100 text-pink-700 text-xs px-2 py-0.5 rounded-full">${p.mood}</span>`
          : '<span class="text-gray-300 text-xs">—</span>'}
      </td>
      <!-- 메모 (클릭 → 모달) -->
      <td class="px-3 py-3 text-gray-500 text-xs max-w-[120px] ${p.memo ? 'expandable' : ''}"
          onclick="${p.memo ? `showTripPlaceText(${p.tripPlaceId}, 'memo', '메모')` : ''}">
        <div class="truncate">${escHtml(p.memo || '—')}</div>
      </td>
      <td class="px-3 py-3">${sourceColor(p.source)}</td>
      <td class="px-3 py-3">
        ${p.openingHours
          ? `<button onclick="showJsonDetail(${p.tripPlaceId}, 'openingHours', '영업시간')"
                     class="text-xs text-blue-500 hover:text-blue-700 underline whitespace-nowrap">보기</button>`
          : '<span class="text-gray-300 text-xs">—</span>'}
      </td>
      <!-- 리뷰 요약 (클릭 → 모달) -->
      <td class="px-3 py-3 max-w-[180px]">
        ${p.reviewData ? `
          <div class="flex flex-col gap-1">
            ${reviewSummary
              ? `<div class="text-xs text-gray-600 truncate expandable"
                          onclick="showTripPlaceText(${p.tripPlaceId}, 'reviewSummary', '리뷰 요약')"
                     >${escHtml(reviewSummary)}</div>`
              : ''}
            <button onclick="showJsonDetail(${p.tripPlaceId}, 'reviewData', '리뷰 전체')"
                    class="text-xs text-blue-500 hover:text-blue-700 underline whitespace-nowrap self-start">
              ${reviewSummary ? '전체 보기' : '보기'}
            </button>
          </div>
        ` : '<span class="text-gray-300 text-xs">—</span>'}
      </td>
      <td class="px-3 py-3 whitespace-nowrap text-xs">
        ${p.rating != null ? `⭐ ${p.rating.toFixed(1)}` : '—'}
      </td>
      <td class="px-3 py-3 text-xs text-gray-500 text-center">
        ${p.userRatingsTotal != null ? p.userRatingsTotal.toLocaleString() : '—'}
      </td>
      <td class="px-3 py-3" id="tpr-status-${sid}">${statusBadge}</td>
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

// ── 여행 장소 텍스트 전체 보기 ─────────────────────────────────────────────
function showTripPlaceText(tripPlaceId, field, label) {
  const data = tripPlaceJsonData[tripPlaceId];
  if (!data) return;

  let text = null;
  if (field === 'reviewSummary') {
    text = extractReviewSummary(data.reviewData);
  } else {
    text = data[field];
  }
  if (text) showTextModal(label, text);
}

// ── JSON 데이터 팝업 (영업시간 / 리뷰 원시 데이터) ────────────────────────
function showJsonDetail(tripPlaceId, field, label) {
  const raw = tripPlaceJsonData[tripPlaceId]?.[field];
  if (!raw) { alert(`[${label}]\n\n데이터 없음`); return; }
  try {
    showTextModal(label, JSON.stringify(JSON.parse(raw), null, 2));
  } catch {
    showTextModal(label, raw);
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

// ── 무드 선호도 배지 로드 (요구사항 1: 점수 표기 포함) ───────────────────
const MOOD_STYLE = {
  HEALING: 'bg-green-100 text-green-700',
  ACTIVE:  'bg-orange-100 text-orange-700',
  TRENDY:  'bg-pink-100 text-pink-700',
  CLASSIC: 'bg-amber-100 text-amber-700',
  LOCAL:   'bg-teal-100 text-teal-700',
};

async function loadUserMoodBadges(userId) {
  const cell = document.getElementById(`mood-cell-${userId}`);
  if (!cell) return;
  cell.innerHTML = '<span class="text-xs text-gray-300">로딩...</span>';
  try {
    const prefs = await apiFetch(`/api/admin/users/${userId}/preferences`);
    if (!prefs || prefs.length === 0) {
      cell.innerHTML = '<span class="text-xs text-gray-300">이력 없음</span>';
      return;
    }
    cell.innerHTML = prefs.map(p => {
      const cls   = MOOD_STYLE[p.mood] || 'bg-gray-100 text-gray-500';
      const score = p.score != null ? p.score.toFixed(1) : '0';
      // [#무드명 (점수점)] 형식
      return `<span class="${cls} text-xs font-semibold px-2 py-0.5 rounded-full mr-1 mb-1 inline-block whitespace-nowrap">
        #${p.mood} (${score}점)</span>`;
    }).join('');
  } catch (_) {
    cell.innerHTML = '<span class="text-xs text-red-400">오류</span>';
  }
}

// ════════════════════════════════════════════════════════════════════════════
// ── 알림 관제 탭
// ════════════════════════════════════════════════════════════════════════════
async function loadNotifications() {
  show('n-loading'); hide('n-table-wrap'); hide('n-empty');
  try {
    allNotifications = await apiFetch('/api/admin/notifications');
    updateNotificationStats(allNotifications);
    applyNotificationFilter();
  } catch (ex) {
    document.getElementById('n-loading').textContent = '불러오기 실패: ' + ex.message;
  }
}

function applyNotificationFilter() {
  const q    = (document.getElementById('n-search').value || '').toLowerCase();
  const push = document.getElementById('n-filter-push').value;
  const f = allNotifications.filter(n =>
    (!push || n.pushStatus === push) &&
    (!q || [
      String(n.userId  || ''),
      String(n.planId  || ''),
      (n.userEmail  || '').toLowerCase(),
      (n.userName   || '').toLowerCase(),
      (n.planName   || '').toLowerCase(),
      (n.tripTitle  || '').toLowerCase()
    ].some(v => v.includes(q)))
  );
  renderNotificationsTable(f);
  document.getElementById('n-count').textContent =
    `총 ${f.length}건 (전체 ${allNotifications.length}건)`;
}

function updateNotificationStats(list) {
  const sent = list.filter(n => n.pushStatus === '발송됨').length;
  document.getElementById('n-stat-total').textContent   = list.length;
  document.getElementById('n-stat-sent').textContent    = sent;
  document.getElementById('n-stat-pending').textContent = list.length - sent;
}

function renderNotificationsTable(list) {
  hide('n-loading');
  if (list.length === 0) { show('n-empty'); hide('n-table-wrap'); return; }
  hide('n-empty'); show('n-table-wrap');

  document.getElementById('n-tbody').innerHTML = list.map(n => {
    const pushBadge = n.pushStatus === '발송됨'
      ? '<span class="bg-green-100 text-green-700 text-xs font-semibold px-2 py-0.5 rounded-full">발송됨</span>'
      : '<span class="bg-yellow-100 text-yellow-700 text-xs font-semibold px-2 py-0.5 rounded-full">미발송</span>';

    return `
    <tr class="hover:bg-gray-50 transition">
      <!-- 알림 ID -->
      <td class="px-4 py-3 text-gray-400 font-mono text-xs whitespace-nowrap">${n.notificationId ?? '—'}</td>

      <!-- 사용자: 닉네임 + 이메일 + ID -->
      <td class="px-4 py-3 min-w-[140px]">
        ${n.userName
          ? `<div class="font-semibold text-xs text-gray-800">${escHtml(n.userName)}</div>`
          : ''}
        ${n.userEmail
          ? `<div class="text-xs text-gray-500">${escHtml(n.userEmail)}</div>`
          : ''}
        <div class="text-xs text-gray-300">ID: ${n.userId ?? '—'}</div>
      </td>

      <!-- 여행 / 장소 -->
      <td class="px-4 py-3 min-w-[160px]">
        ${n.tripTitle
          ? `<div class="font-semibold text-xs text-indigo-700 truncate max-w-[180px]" title="${escHtml(n.tripTitle)}">${escHtml(n.tripTitle)}</div>`
          : ''}
        ${n.planName
          ? `<div class="text-xs text-gray-600 truncate max-w-[180px]" title="${escHtml(n.planName)}">📍 ${escHtml(n.planName)}</div>`
          : ''}
        <div class="text-xs text-gray-300">planId: ${n.planId ?? '—'}</div>
      </td>

      <!-- 타입 -->
      <td class="px-4 py-3">
        <span class="bg-sky-100 text-sky-700 text-xs px-2 py-0.5 rounded-full whitespace-nowrap">${escHtml(n.type || '—')}</span>
      </td>

      <!-- 제목 -->
      <td class="px-4 py-3 text-xs text-gray-700 max-w-[160px]">
        <div class="truncate" title="${escHtml(n.title)}">${escHtml(n.title || '—')}</div>
      </td>

      <!-- 강수 확률 -->
      <td class="px-4 py-3 text-center text-xs font-semibold text-blue-600 whitespace-nowrap">
        ${escHtml(n.precipitationProb || '—')}
      </td>

      <!-- 푸시 상태 -->
      <td class="px-4 py-3">${pushBadge}</td>

      <!-- 푸시 발송 시각 -->
      <td class="px-4 py-3 text-gray-400 text-xs whitespace-nowrap">${fmtDate(n.pushSentAt)}</td>

      <!-- 생성 시각 -->
      <td class="px-4 py-3 text-gray-400 text-xs whitespace-nowrap">${fmtDate(n.createdAt)}</td>

      <!-- 액션: 재알림 버튼 -->
      <td class="px-4 py-3">
        <button id="resend-btn-${n.notificationId}"
                onclick="resendNotification(${n.notificationId})"
                class="bg-violet-500 hover:bg-violet-600 disabled:opacity-40 disabled:cursor-not-allowed
                       text-white text-xs px-3 py-1 rounded transition whitespace-nowrap">
          재알림
        </button>
      </td>
    </tr>`;
  }).join('');
}

// ── 날씨 알림 수동 재발송 (요구사항 4) ────────────────────────────────────
async function resendNotification(notificationId) {
  if (!confirm('이 알림을 해당 사용자에게 다시 발송하시겠습니까?\n\n푸시 토큰이 없거나 앱을 삭제한 경우 발송되지 않을 수 있습니다.')) return;
  const btn = document.getElementById(`resend-btn-${notificationId}`);
  if (btn) { btn.disabled = true; btn.textContent = '발송 중...'; }
  try {
    await apiFetch(`/api/admin/notifications/${notificationId}/resend`, { method: 'POST' });
    alert('재발송 완료되었습니다.');

    // 로컬 상태 업데이트 (새로고침 없이 푸시 상태 반영)
    const n = allNotifications.find(x => x.notificationId === notificationId);
    if (n) {
      n.pushStatus = '발송됨';
      n.pushSentAt = new Date().toISOString();
      updateNotificationStats(allNotifications);
      applyNotificationFilter(); // 테이블 재렌더링
    }
  } catch (ex) {
    alert('재발송 실패: ' + ex.message);
    if (btn) { btn.disabled = false; btn.textContent = '재알림'; }
  }
}

// ════════════════════════════════════════════════════════════════════════════
// ── 장소 관리 탭 (DB Places)
// ════════════════════════════════════════════════════════════════════════════
async function loadDbPlaces() {
  show('p-loading'); hide('p-table-wrap'); hide('p-empty');
  placesSortCol = 'id';
  placesSortDir = 'asc';
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
  renderSortedDbPlaces(f);
  document.getElementById('p-count').textContent = `총 ${f.length}개 (전체 ${allPlaces.length}개)`;
}

function updatePlaceStats(places) {
  const complete = places.filter(p => p.analysisStatus === 'COMPLETE').length;
  document.getElementById('p-stat-total').textContent    = places.length;
  document.getElementById('p-stat-complete').textContent = complete;
  document.getElementById('p-stat-pending').textContent  = places.length - complete;
}

// ── 장소 관리 정렬 ─────────────────────────────────────────────────────────
function sortPlaces(col) {
  if (placesSortCol === col) {
    placesSortDir = placesSortDir === 'asc' ? 'desc' : 'asc';
  } else {
    placesSortCol = col;
    placesSortDir = 'asc';
  }
  applyPlaceFilter();
}

function renderSortedDbPlaces(filtered) {
  const sorted = [...filtered].sort((a, b) => {
    const av = a[placesSortCol] ?? '';
    const bv = b[placesSortCol] ?? '';
    const cmp = (typeof av === 'number' && typeof bv === 'number')
      ? av - bv
      : String(av).localeCompare(String(bv), 'ko');
    return placesSortDir === 'asc' ? cmp : -cmp;
  });

  // 정렬 아이콘 업데이트
  PLACE_SORT_COLS.forEach(col => {
    const el = document.getElementById(`ps-${col}`);
    if (!el) return;
    if (col === placesSortCol) {
      el.textContent = placesSortDir === 'asc' ? '▲' : '▼';
      el.className   = 'text-indigo-500';
    } else {
      el.textContent = '⇅';
      el.className   = 'text-gray-300';
    }
  });

  renderDbPlacesTable(sorted);
}

function renderDbPlacesTable(places) {
  hide('p-loading');
  if (places.length === 0) { show('p-empty'); hide('p-table-wrap'); return; }
  hide('p-empty'); show('p-table-wrap');

  document.getElementById('p-tbody').innerHTML = places.map(p => {
    const sid = safe(p.googlePlaceId);

    // JSONB + 텍스트 데이터 전역 Map 보관 (요구사항 2: 텍스트 셀 전체 보기용)
    placeJsonData[sid] = {
      openingHours:  p.openingHours,
      reviewData:    p.reviewData,
      address:       p.address,
      category:      p.category,
      reviewSummary: extractReviewSummary(p.reviewData)
    };

    const statusBadge = p.analysisStatus === 'COMPLETE'
      ? `<span class="bg-green-100 text-green-700 text-xs font-semibold px-2 py-0.5 rounded-full">완료</span>`
      : `<span id="p-badge-${sid}" class="bg-yellow-100 text-yellow-700 text-xs font-semibold px-2 py-0.5 rounded-full">미완료</span>`;

    const businessBadge = (() => {
      const map = {
        OPERATIONAL:        'bg-green-100 text-green-700',
        CLOSED_TEMPORARILY: 'bg-yellow-100 text-yellow-700',
        CLOSED_PERMANENTLY: 'bg-red-100 text-red-700'
      };
      if (!p.businessStatus) return '<span class="text-gray-300 text-xs">—</span>';
      const cls = map[p.businessStatus] || 'bg-gray-100 text-gray-500';
      return `<span class="${cls} text-xs px-2 py-0.5 rounded-full">${p.businessStatus}</span>`;
    })();

    const priceBadge = p.priceLevel != null
      ? `<span class="text-xs text-gray-600">${'₩'.repeat(p.priceLevel + 1)}</span>`
      : '<span class="text-gray-300 text-xs">—</span>';

    const reviewSummary = extractReviewSummary(p.reviewData);

    return `
    <tr class="hover:bg-gray-50 transition">
      <!-- ID -->
      <td class="px-3 py-3 text-gray-400 font-mono text-xs whitespace-nowrap">${p.id ?? '—'}</td>
      <!-- 장소명 -->
      <td class="px-3 py-3 font-medium max-w-[160px]">
        <div class="truncate" title="${escHtml(p.name)}">${escHtml(p.name)}</div>
      </td>
      <!-- Google Place ID -->
      <td class="px-3 py-3 text-gray-400 text-xs max-w-[130px] truncate"
          title="${escHtml(p.googlePlaceId)}">${escHtml(p.googlePlaceId || '—')}</td>
      <!-- 주소 (클릭 → 모달) — 요구사항 2 -->
      <td class="px-3 py-3 text-gray-500 text-xs max-w-[180px] expandable"
          onclick="showPlaceText('${escJs(sid)}', 'address', '주소')">
        <div class="truncate">${escHtml(p.address || '—')}</div>
      </td>
      <!-- 카테고리 (클릭 → 모달) — 요구사항 2 -->
      <td class="px-3 py-3 text-gray-500 text-xs max-w-[120px] expandable"
          onclick="showPlaceText('${escJs(sid)}', 'category', '카테고리')">
        <div class="truncate">${escHtml(p.category || '—')}</div>
      </td>
      <!-- 타입 -->
      <td class="px-3 py-3">
        ${p.type  ? `<span class="bg-indigo-100 text-indigo-700 text-xs px-2 py-0.5 rounded-full">${p.type}</span>`  : '<span class="text-gray-300 text-xs">—</span>'}
      </td>
      <!-- 스페이스 -->
      <td class="px-3 py-3">
        ${p.space ? `<span class="bg-purple-100 text-purple-700 text-xs px-2 py-0.5 rounded-full">${p.space}</span>` : '<span class="text-gray-300 text-xs">—</span>'}
      </td>
      <!-- 분위기 -->
      <td class="px-3 py-3">
        ${p.mood  ? `<span class="bg-pink-100 text-pink-700 text-xs px-2 py-0.5 rounded-full">${p.mood}</span>`   : '<span class="text-gray-300 text-xs">—</span>'}
      </td>
      <!-- 위도 -->
      <td class="px-3 py-3 text-xs text-gray-500 font-mono whitespace-nowrap">
        ${p.latitude != null ? p.latitude.toFixed(6) : '—'}
      </td>
      <!-- 경도 -->
      <td class="px-3 py-3 text-xs text-gray-500 font-mono whitespace-nowrap">
        ${p.longitude != null ? p.longitude.toFixed(6) : '—'}
      </td>
      <!-- 평점 -->
      <td class="px-3 py-3 whitespace-nowrap text-xs">
        ${p.rating != null ? `⭐ ${p.rating.toFixed(1)}` : '—'}
      </td>
      <!-- 리뷰수 -->
      <td class="px-3 py-3 text-xs text-gray-500 text-center">
        ${p.userRatingsTotal != null ? p.userRatingsTotal.toLocaleString() : '—'}
      </td>
      <!-- 가격 -->
      <td class="px-3 py-3 text-center">${priceBadge}</td>
      <!-- 영업상태 -->
      <td class="px-3 py-3">${businessBadge}</td>
      <!-- 전화번호 -->
      <td class="px-3 py-3 text-xs text-gray-500 whitespace-nowrap">
        ${p.phoneNumber ? escHtml(p.phoneNumber) : '<span class="text-gray-300">—</span>'}
      </td>
      <!-- 웹사이트 -->
      <td class="px-3 py-3 text-xs max-w-[120px]">
        ${p.website
          ? `<a href="${escHtml(p.website)}" target="_blank" rel="noopener"
                class="text-blue-500 hover:text-blue-700 underline truncate block"
                title="${escHtml(p.website)}">링크</a>`
          : '<span class="text-gray-300">—</span>'}
      </td>
      <!-- 영업시간 (JSONB) -->
      <td class="px-3 py-3">
        ${p.openingHours
          ? `<button onclick="showPlaceJsonDetail('${escJs(sid)}', 'openingHours', '영업시간')"
                     class="text-xs text-blue-500 hover:text-blue-700 underline whitespace-nowrap">보기</button>`
          : '<span class="text-gray-300 text-xs">—</span>'}
      </td>
      <!-- 리뷰 요약 (클릭 → 모달) — 요구사항 2 -->
      <td class="px-3 py-3 max-w-[180px]">
        ${p.reviewData ? `
          <div class="flex flex-col gap-1">
            ${reviewSummary
              ? `<div class="text-xs text-gray-600 truncate expandable"
                          onclick="showPlaceText('${escJs(sid)}', 'reviewSummary', '리뷰 요약')"
                     >${escHtml(reviewSummary)}</div>`
              : ''}
            <button onclick="showPlaceJsonDetail('${escJs(sid)}', 'reviewData', '리뷰 전체')"
                    class="text-xs text-blue-500 hover:text-blue-700 underline whitespace-nowrap self-start">
              ${reviewSummary ? '전체 보기' : '보기'}
            </button>
          </div>
        ` : '<span class="text-gray-300 text-xs">—</span>'}
      </td>
      <!-- 마지막 분석 -->
      <td class="px-3 py-3 text-gray-400 text-xs whitespace-nowrap">${fmtDate(p.lastSyncedAt)}</td>
      <!-- 상태 -->
      <td class="px-3 py-3" id="p-status-${sid}">${statusBadge}</td>
      <!-- 액션 -->
      <td class="px-3 py-3">
        ${p.googlePlaceId
          ? `<button id="p-btn-${sid}"
                     onclick="triggerReanalyze('${escJs(p.googlePlaceId)}', '${escJs(p.name)}', 'p')"
                     class="bg-amber-500 hover:bg-amber-600 disabled:opacity-50 disabled:cursor-not-allowed text-white text-xs px-3 py-1 rounded transition whitespace-nowrap">
               재분석
             </button>`
          : '<span class="text-gray-300 text-xs">—</span>'}
      </td>
    </tr>`;
  }).join('');
}

// ── 장소 관리: 텍스트 셀 전체 보기 (요구사항 2) ──────────────────────────
function showPlaceText(sid, field, label) {
  const text = placeJsonData[sid]?.[field];
  if (text) showTextModal(label, text);
}

// ── 장소 관리: JSON 팝업 (영업시간 / 리뷰 원시 데이터) ──────────────────
function showPlaceJsonDetail(sid, field, label) {
  const raw = placeJsonData[sid]?.[field];
  if (!raw) { showTextModal(label, '데이터 없음'); return; }
  try {
    showTextModal(label, JSON.stringify(JSON.parse(raw), null, 2));
  } catch {
    showTextModal(label, raw);
  }
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
async function apiFetch(url, opts = {}, _retry = false) {
  const res = await fetch(url, {
    ...opts,
    headers: {
      'Content-Type': 'application/json',
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
      ...(opts.headers || {})
    }
  });

  // 401: 토큰 만료 → refresh 시도 후 1회 재시도
  if (res.status === 401 && !_retry && refreshToken) {
    const refreshed = await tryRefreshToken();
    if (refreshed) return apiFetch(url, opts, true);  // 새 토큰으로 재시도
    handleLogout(); throw new Error('세션이 만료되었습니다. 다시 로그인해 주세요.');
  }
  if (res.status === 401) { handleLogout(); throw new Error('세션이 만료되었습니다. 다시 로그인해 주세요.'); }
  if (res.status === 403) { handleLogout(); throw new Error('관리자 권한이 없습니다.'); }
  if (!res.ok) {
    const t = await res.text().catch(() => '');
    throw new Error(t || `HTTP ${res.status}`);
  }
  const t = await res.text();
  if (!t) return null;
  try { return JSON.parse(t); } catch { return t; }
}

// access token 자동 갱신 — 성공 시 true, 실패 시 false
async function tryRefreshToken() {
  try {
    const res = await fetch('/api/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken })
    });
    if (!res.ok) return false;
    const data = await res.json();
    accessToken = data.access_token;
    localStorage.setItem('admin_token', accessToken);
    return true;
  } catch (_) {
    return false;
  }
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

// ════════════════════════════════════════════════════════════════════════════
// 환경 감지 및 배지 초기화
// ════════════════════════════════════════════════════════════════════════════
function initEnvBadge() {
  const host = window.location.hostname;

  let env, label, badgeClass, switchHref, switchLabel, switchClass;

  if (host === 'api.planb-travel.cloud') {
    env        = 'prod';
    label      = '운영';
    badgeClass = 'bg-red-100 text-red-700';
    switchHref  = 'https://api-dev.planb-travel.cloud/admin';
    switchLabel = '개발 서버로 이동 →';
    switchClass = 'border-blue-300 text-blue-600 hover:bg-blue-50';
  } else if (host === 'api-dev.planb-travel.cloud') {
    env        = 'dev';
    label      = '개발';
    badgeClass = 'bg-blue-100 text-blue-700';
    switchHref  = 'https://api.planb-travel.cloud/admin/index.html';
    switchLabel = '운영 서버로 이동 →';
    switchClass = 'border-red-300 text-red-600 hover:bg-red-50';
  } else {
    env        = 'local';
    label      = '로컬';
    badgeClass = 'bg-gray-100 text-gray-600';
    switchHref  = null;
    switchLabel = null;
    switchClass = '';
  }

  // 헤더 배지
  const badge = document.getElementById('env-badge');
  if (badge) {
    badge.textContent = label;
    badge.className   = `px-2 py-0.5 rounded-full text-xs font-semibold ${badgeClass}`;
  }

  // 로그인 화면 배지
  const loginBadge = document.getElementById('login-env-badge');
  if (loginBadge) {
    loginBadge.textContent = label;
    loginBadge.className   = `mt-2 inline-block px-3 py-0.5 rounded-full text-xs font-semibold ${badgeClass}`;
  }

  // 환경 이동 버튼 (로컬이면 숨김)
  const switchBtn = document.getElementById('env-switch-btn');
  if (switchBtn && switchHref) {
    switchBtn.href        = switchHref;
    switchBtn.textContent = switchLabel;
    switchBtn.className   = `text-xs font-semibold px-3 py-1.5 rounded-lg border transition ${switchClass}`;
  }
}
