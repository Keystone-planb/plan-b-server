package com.planb.planb_backend.domain.bookmark.dto;

import com.planb.planb_backend.domain.bookmark.entity.Bookmark;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class BookmarkResponse {

    private Long bookmarkId;
    private String googlePlaceId;
    private String name;
    private String category;
    private String address;
    private Double lat;
    private Double lng;
    private LocalDateTime createdAt;

    public static BookmarkResponse from(Bookmark bookmark) {
        return BookmarkResponse.builder()
                .bookmarkId(bookmark.getId())
                .googlePlaceId(bookmark.getGooglePlaceId())
                .name(bookmark.getName())
                .category(bookmark.getCategory())
                .address(bookmark.getAddress())
                .lat(bookmark.getLat())
                .lng(bookmark.getLng())
                .createdAt(bookmark.getCreatedAt())
                .build();
    }
}
