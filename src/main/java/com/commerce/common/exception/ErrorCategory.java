package com.commerce.common.exception;

/**
 * 도메인 예외의 의미 분류. HTTP 상태코드를 모른다(transport-agnostic).
 * 카테고리 → HttpStatus 매핑은 HTTP를 아는 경계(GlobalExceptionHandler·인증 필터 등)가
 * {@link ErrorCategoryHttpStatus}로 소유한다.
 */
public enum ErrorCategory {
	INVALID,
	UNAUTHORIZED,
	FORBIDDEN,
	NOT_FOUND,
	CONFLICT,
	UPSTREAM_ERROR,
	INTERNAL
}
