package com.commerce.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

@DisplayName("ErrorCategory → HttpStatus 매핑")
class ErrorCategoryHttpStatusTest {

	@Test
	@DisplayName("각 카테고리는 기대 상태코드로 매핑된다 (외부 응답 계약 잠금)")
	void mapsEachCategoryToExpectedStatus() {
		assertThat(ErrorCategoryHttpStatus.of(ErrorCategory.INVALID)).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(ErrorCategoryHttpStatus.of(ErrorCategory.UNAUTHORIZED)).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(ErrorCategoryHttpStatus.of(ErrorCategory.FORBIDDEN)).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(ErrorCategoryHttpStatus.of(ErrorCategory.NOT_FOUND)).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(ErrorCategoryHttpStatus.of(ErrorCategory.CONFLICT)).isEqualTo(HttpStatus.CONFLICT);
		assertThat(ErrorCategoryHttpStatus.of(ErrorCategory.UPSTREAM_ERROR)).isEqualTo(HttpStatus.BAD_GATEWAY);
		assertThat(ErrorCategoryHttpStatus.of(ErrorCategory.INTERNAL)).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	}
}
