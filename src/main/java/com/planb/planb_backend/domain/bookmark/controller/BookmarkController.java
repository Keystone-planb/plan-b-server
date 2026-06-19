package com.planb.planb_backend.domain.bookmark.controller;

import com.planb.planb_backend.domain.bookmark.dto.AddBookmarkRequest;
import com.planb.planb_backend.domain.bookmark.dto.BookmarkResponse;
import com.planb.planb_backend.domain.bookmark.service.BookmarkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "즐겨찾기", description = "장소 즐겨찾기 추가/조회/삭제 API")
@RestController
@RequestMapping("/api/bookmarks")
@RequiredArgsConstructor
public class BookmarkController {

    private final BookmarkService bookmarkService;

    @Operation(summary = "즐겨찾기 추가", description = "장소를 즐겨찾기에 추가합니다. 동일 장소 중복 추가 시 409 응답.")
    @PostMapping
    public ResponseEntity<BookmarkResponse> addBookmark(
            @Valid @RequestBody AddBookmarkRequest request,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(bookmarkService.addBookmark(authentication.getName(), request));
    }

    @Operation(summary = "즐겨찾기 목록 조회", description = "로그인한 유저의 즐겨찾기 목록을 최신순으로 반환합니다.")
    @GetMapping
    public ResponseEntity<List<BookmarkResponse>> getBookmarks(Authentication authentication) {
        return ResponseEntity.ok(bookmarkService.getBookmarks(authentication.getName()));
    }

    @Operation(summary = "즐겨찾기 삭제", description = "즐겨찾기를 삭제합니다. 본인 것이 아니면 403 응답.")
    @DeleteMapping("/{bookmarkId}")
    public ResponseEntity<Void> deleteBookmark(
            @PathVariable Long bookmarkId,
            Authentication authentication) {
        bookmarkService.deleteBookmark(authentication.getName(), bookmarkId);
        return ResponseEntity.noContent().build();
    }
}
