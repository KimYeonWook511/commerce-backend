package com.commerce.member.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MemberTest {

	@DisplayName("일반 회원을 생성하면 기본 권한은 ROLE_USER다")
	@Test
	void createUser_whenValidArguments_createMemberWithUserRole() {
		// when
		Member member = Member.createUser("test@example.com", "hashed-password", "user1");

		// then
		assertThat(member.getEmail()).isEqualTo("test@example.com");
		assertThat(member.getPassword()).isEqualTo("hashed-password");
		assertThat(member.getUsername()).isEqualTo("user1");
		assertThat(member.getRole()).isEqualTo(MemberRole.ROLE_USER);
	}

	@DisplayName("일반 회원 생성 시 이메일은 필수다")
	@Test
	void createUser_whenEmailBlank_throwException() {
		assertThatThrownBy(() -> Member.createUser(" ", "hashed-password", "user1"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@DisplayName("일반 회원 생성 시 비밀번호 해시는 필수다")
	@Test
	void createUser_whenPasswordHashBlank_throwException() {
		assertThatThrownBy(() -> Member.createUser("test@example.com", " ", "user1"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@DisplayName("일반 회원 생성 시 사용자명은 필수다")
	@Test
	void createUser_whenUsernameBlank_throwException() {
		assertThatThrownBy(() -> Member.createUser("test@example.com", "hashed-password", " "))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
