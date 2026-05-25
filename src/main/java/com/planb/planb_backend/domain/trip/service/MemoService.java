package com.planb.planb_backend.domain.trip.service;

import com.planb.planb_backend.domain.trip.dto.MemoResponse;
import com.planb.planb_backend.domain.trip.entity.TripPlace;
import com.planb.planb_backend.domain.trip.entity.TripPlaceMemo;
import com.planb.planb_backend.domain.trip.repository.TripPlaceMemoRepository;
import com.planb.planb_backend.domain.trip.repository.TripPlaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoService {

    private final TripPlaceMemoRepository memoRepository;
    private final TripPlaceRepository     tripPlaceRepository;

    /**
     * POST /api/plans/{planId}/memos — 메모 추가
     */
    @Transactional
    public MemoResponse addMemo(String email, Long tripPlaceId, String content) {
        TripPlace tripPlace = tripPlaceRepository.findByIdAndUserEmail(tripPlaceId, email)
                .orElseThrow(() -> new IllegalArgumentException("일정을 찾을 수 없거나 접근 권한이 없습니다."));

        TripPlaceMemo memo = TripPlaceMemo.builder()
                .tripPlace(tripPlace)
                .content(content)
                .build();

        TripPlaceMemo saved = memoRepository.save(memo);
        log.info("[Memo] 메모 추가 — tripPlaceId={}, memoId={}", tripPlaceId, saved.getId());
        return MemoResponse.from(saved);
    }

    /**
     * PATCH /api/plans/{planId}/memos/{memoId} — 메모 수정
     */
    @Transactional
    public MemoResponse updateMemo(String email, Long memoId, String content) {
        TripPlaceMemo memo = memoRepository.findByIdAndUserEmail(memoId, email)
                .orElseThrow(() -> new IllegalArgumentException("메모를 찾을 수 없거나 접근 권한이 없습니다."));

        memo.updateContent(content);
        log.info("[Memo] 메모 수정 — memoId={}", memoId);
        return MemoResponse.from(memo);
    }

    /**
     * DELETE /api/plans/{planId}/memos/{memoId} — 메모 삭제
     */
    @Transactional
    public void deleteMemo(String email, Long memoId) {
        TripPlaceMemo memo = memoRepository.findByIdAndUserEmail(memoId, email)
                .orElseThrow(() -> new IllegalArgumentException("메모를 찾을 수 없거나 접근 권한이 없습니다."));

        memoRepository.delete(memo);
        log.info("[Memo] 메모 삭제 — memoId={}", memoId);
    }
}
