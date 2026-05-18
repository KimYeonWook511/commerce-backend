package com.commerce.member.infrastructure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.commerce.common.jpa.JpaConfig;
import com.commerce.member.domain.Member;
import com.commerce.support.TestcontainersSupport;

@Tag("docker")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@ActiveProfiles("test")
class DuplicateKeyExceptionMappingTest {

	@DynamicPropertySource
	static void registerContainers(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
	}

	@Autowired
	private JpaMemberRepository jpaMemberRepository;

	@DisplayName("이메일 unique 위반 시 DuplicateKeyException이 발생한다")
	@Test
	void save_whenEmailDuplicated_throwDuplicateKeyException() {
		// Given — 동일 이메일로 첫 번째 엔티티 저장
		Member firstMember = Member.createUser("dup@example.com", "hashed-password-60chars-xxxxxxxxxxxxxxxxx", "user1");
		jpaMemberRepository.saveAndFlush(firstMember);

		// When & Then — JPA 경로(HibernateJpaDialect → jdbcExceptionTranslator)로 DuplicateKeyException 변환 검증
		Member secondMember = Member.createUser("dup@example.com", "hashed-password-60chars-xxxxxxxxxxxxxxxxy", "user2");
		assertThatThrownBy(() -> jpaMemberRepository.saveAndFlush(secondMember))
			.isInstanceOf(DuplicateKeyException.class);
	}
}
