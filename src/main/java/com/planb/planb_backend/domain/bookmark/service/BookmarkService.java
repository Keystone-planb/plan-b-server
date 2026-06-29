package com.planb.planb_backend.domain.bookmark.service;

import com.planb.planb_backend.config.exception.BusinessException;
import com.planb.planb_backend.config.exception.ErrorCode;
import com.planb.planb_backend.domain.bookmark.dto.AddBookmarkRequest;
import com.planb.planb_backend.domain.bookmark.dto.BookmarkResponse;
import com.planb.planb_backend.domain.bookmark.entity.Bookmark;
import com.planb.planb_backend.domain.bookmark.repository.BookmarkRepository;
import com.planb.planb_backend.domain.user.entity.User;
import com.planb.planb_backend.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BookmarkService {

    private final BookmarkRepository bookmarkRepository;
    private final UserRepository userRepository;

    @Transactional
    public BookmarkResponse addBookmark(String email, AddBookmarkRequest request) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        if (bookmarkRepository.existsByUserIdAndGooglePlaceId(user.getId(), request.getGooglePlaceId())) {
            throw new BusinessException(ErrorCode.BOOKMARK_ALREADY_EXISTS);
        }

        Bookmark bookmark = Bookmark.builder()
                .userId(user.getId())
                .googlePlaceId(request.getGooglePlaceId())
                .name(request.getName())
                .category(request.getCategory())
                .address(request.getAddress())
                .lat(request.getLat())
                .lng(request.getLng())
                .build();

        return BookmarkResponse.from(bookmarkRepository.save(bookmark));
    }

    @Transactional(readOnly = true)
    public List<BookmarkResponse> getBookmarks(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        return bookmarkRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(BookmarkResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteBookmark(String email, Long bookmarkId) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        Bookmark bookmark = bookmarkRepository.findById(bookmarkId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BOOKMARK_NOT_FOUND));

        if (!bookmark.getUserId().equals(user.getId())) {
            throw new BusinessException(ErrorCode.BOOKMARK_FORBIDDEN);
        }

        bookmarkRepository.delete(bookmark);
    }
}
