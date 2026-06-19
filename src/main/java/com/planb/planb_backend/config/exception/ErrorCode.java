package com.planb.planb_backend.config.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    SOCIAL_USER_CANNOT_CHANGE_PASSWORD(HttpStatus.BAD_REQUEST, "소셜 로그인 계정은 비밀번호를 변경할 수 없습니다."),
    INVALID_PASSWORD(HttpStatus.BAD_REQUEST, "현재 비밀번호가 올바르지 않습니다."),

    BOOKMARK_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 즐겨찾기에 추가된 장소입니다."),
    BOOKMARK_NOT_FOUND(HttpStatus.NOT_FOUND, "즐겨찾기를 찾을 수 없습니다."),
    BOOKMARK_FORBIDDEN(HttpStatus.FORBIDDEN, "본인의 즐겨찾기만 삭제할 수 있습니다.");

    private final HttpStatus status;
    private final String message;
}
