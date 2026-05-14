package com.commerce.auth.application.port;

import java.util.Optional;

public interface RefreshTokenStore {

	void save(Long memberId, String refreshToken);

	Optional<String> get(Long memberId);

	void delete(Long memberId);
}
