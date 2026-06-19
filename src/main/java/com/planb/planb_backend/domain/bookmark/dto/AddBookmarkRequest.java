package com.planb.planb_backend.domain.bookmark.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class AddBookmarkRequest {

    @NotBlank(message = "googlePlaceId는 필수입니다.")
    private String googlePlaceId;

    @NotBlank(message = "name은 필수입니다.")
    private String name;

    private String category;

    private String address;

    private Double lat;

    private Double lng;
}
