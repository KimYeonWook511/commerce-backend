package com.commerce.auth.infrastructure;

/**
 * refresh token 저장소(Redis)가 일시적으로 사용 불가함을 표현하는 예외.
 * AuthExceptionHandler가 받아 AUTH-500-1 응답으로 매핑한다.
 */
public class RefreshTokenStoreUnavailableException extends RuntimeException {

	public RefreshTokenStoreUnavailableException(Throwable cause) {
		super(cause);
	}
}
