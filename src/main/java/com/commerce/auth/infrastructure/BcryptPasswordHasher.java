package com.commerce.auth.infrastructure;

import org.mindrot.jbcrypt.BCrypt;
import org.springframework.stereotype.Component;

import com.commerce.auth.application.port.PasswordHasher;

@Component
public class BcryptPasswordHasher implements PasswordHasher {

	@Override
	public String hash(String rawPassword) {
		return BCrypt.hashpw(rawPassword, BCrypt.gensalt());
	}

	@Override
	public boolean matches(String rawPassword, String hashedPassword) {
		return BCrypt.checkpw(rawPassword, hashedPassword);
	}
}
