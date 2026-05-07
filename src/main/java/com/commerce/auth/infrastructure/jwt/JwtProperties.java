package com.commerce.auth.infrastructure.jwt;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;

@Component
public class JwtProperties {

	@Value("${jwt.access.secret.key}")
	private String accessSecretKeyStr;
	private SecretKey accessSecretKey;

	@Value("${jwt.refresh.secret.key}")
	private String refreshSecretKeyStr;
	private SecretKey refreshSecretKey;

	@Value("${jwt.access.expiration}")
	private Long accessExpiration;

	@Value("${jwt.refresh.expiration}")
	private Long refreshExpiration;

	@PostConstruct
	public void init() {
		accessSecretKey = Keys.hmacShaKeyFor(accessSecretKeyStr.getBytes(StandardCharsets.UTF_8));
		refreshSecretKey = Keys.hmacShaKeyFor(refreshSecretKeyStr.getBytes(StandardCharsets.UTF_8));
	}

	public SecretKey getAccessSecretKey() {
		return accessSecretKey;
	}

	public SecretKey getRefreshSecretKey() {
		return refreshSecretKey;
	}

	public Long getAccessExpiration() {
		return accessExpiration;
	}
	public Long getRefreshExpiration() {
		return refreshExpiration;
	}

}
