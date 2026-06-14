package com.commerce.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.commerce.auth.application.command.AuthSignUpCommand;
import com.commerce.auth.application.port.RefreshTokenStore;
import com.commerce.member.domain.exception.MemberException;
import com.commerce.member.infrastructure.persistence.support.MemberPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;
import com.commerce.support.TestcontainersSupport;

@SpringBootTest
@ActiveProfiles("test")
@Tag("docker")
@Import({PersistenceCleanupTestSupport.class, MemberPersistenceTestSupport.class})
class AuthSignUpServiceIntegrationTest {

	@Autowired
	private AuthSignUpService authSignUpService;

	@Autowired
	private RefreshTokenStore refreshTokenStore;

	@Autowired
	private PersistenceCleanupTestSupport persistenceCleanup;

	@Autowired
	private MemberPersistenceTestSupport memberPersistence;

	@DynamicPropertySource
	static void registerContainers(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
		TestcontainersSupport.registerRedis(registry);
	}

	@AfterEach
	void tearDown() {
		persistenceCleanup.deleteAllInBatch(memberPersistence);
	}

	@DisplayName("회원가입 성공 후 Redis에 refresh token이 저장된다")
	@Test
	void signUp_whenSuccess_storesRefreshTokenInRedis() {
		// Given
		AuthSignUpCommand command = AuthSignUpCommand.builder()
			.email("integration@example.com")
			.password("password123")
			.username("intUser")
			.build();

		// When
		var result = authSignUpService.signUp(command);

		// Then
		assertThat(refreshTokenStore.get(result.getMember().getMemberId())).isPresent();
	}

	@DisplayName("회원가입 실패(중복 이메일) 시 MemberException이 던져진다")
	@Test
	void signUp_whenDuplicateEmail_throwsMemberException() {
		// Given
		AuthSignUpCommand firstCommand = AuthSignUpCommand.builder()
			.email("duplicate@example.com")
			.password("password123")
			.username("firstUser")
			.build();
		authSignUpService.signUp(firstCommand);

		AuthSignUpCommand secondCommand = AuthSignUpCommand.builder()
			.email("duplicate@example.com")
			.password("password456")
			.username("secondUser")
			.build();

		// When / Then
		assertThatThrownBy(() -> authSignUpService.signUp(secondCommand))
			.isInstanceOf(MemberException.class);
	}
}
