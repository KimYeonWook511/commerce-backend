package com.commerce.common.exception;

import org.springframework.http.HttpStatus;

/**
 * ErrorCategory(의미 분류) → HttpStatus 매핑. 도메인 예외가 상태코드를 직접 들지 않게 하는 대신,
 * HTTP를 아는 경계가 이 매핑으로 응답 상태를 정한다.
 *
 * default 없는 switch로 카테고리 누락을 컴파일 타임에 막는다 — 새 카테고리를 추가하면 여기 매핑을
 * 반드시 채워야 컴파일된다. 이 클래스가 카테고리와 HttpStatus를 함께 아는 유일한 지점이다.
 */
public final class ErrorCategoryHttpStatus {

	private ErrorCategoryHttpStatus() {
	}

	public static HttpStatus of(ErrorCategory category) {
		return switch (category) {
			case INVALID -> HttpStatus.BAD_REQUEST;
			case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
			case FORBIDDEN -> HttpStatus.FORBIDDEN;
			case NOT_FOUND -> HttpStatus.NOT_FOUND;
			case CONFLICT -> HttpStatus.CONFLICT;
			case UPSTREAM_ERROR -> HttpStatus.BAD_GATEWAY;
			case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;
		};
	}
}
