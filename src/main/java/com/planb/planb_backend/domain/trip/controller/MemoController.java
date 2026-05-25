package com.planb.planb_backend.domain.trip.controller;

import com.planb.planb_backend.domain.trip.dto.AddMemoRequest;
import com.planb.planb_backend.domain.trip.dto.MemoResponse;
import com.planb.planb_backend.domain.trip.dto.UpdateMemoRequest;
import com.planb.planb_backend.domain.trip.service.MemoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Tag(name = "메모", description = "일정별 메모 추가/수정/삭제 API")
@RestController
@RequestMapping("/api/plans/{planId}/memos")
@RequiredArgsConstructor
public class MemoController {

    private final MemoService memoService;

    @Operation(summary = "메모 추가", description = "특정 일정(planId)에 메모를 추가합니다.")
    @PostMapping
    public ResponseEntity<MemoResponse> addMemo(
            @PathVariable Long planId,
            @Valid @RequestBody AddMemoRequest request,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(memoService.addMemo(authentication.getName(), planId, request.getContent()));
    }

    @Operation(summary = "메모 수정", description = "메모 내용을 수정합니다.")
    @PatchMapping("/{memoId}")
    public ResponseEntity<MemoResponse> updateMemo(
            @PathVariable Long planId,
            @PathVariable Long memoId,
            @Valid @RequestBody UpdateMemoRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(memoService.updateMemo(authentication.getName(), memoId, request.getContent()));
    }

    @Operation(summary = "메모 삭제", description = "메모를 삭제합니다.")
    @DeleteMapping("/{memoId}")
    public ResponseEntity<Void> deleteMemo(
            @PathVariable Long planId,
            @PathVariable Long memoId,
            Authentication authentication) {
        memoService.deleteMemo(authentication.getName(), memoId);
        return ResponseEntity.noContent().build();
    }
}
