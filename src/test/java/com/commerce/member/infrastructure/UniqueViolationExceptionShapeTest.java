package com.commerce.member.infrastructure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
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
class UniqueViolationExceptionShapeTest {

	@DynamicPropertySource
	static void registerContainers(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
	}

	@Autowired
	private JpaMemberRepository jpaMemberRepository;

	@DisplayName("이메일 unique 위반 시 DataIntegrityViolationException(cause=ConstraintViolationException)이 발생한다")
	@Test
	void save_whenEmailDuplicated_throwDataIntegrityViolationException() {
		// Given — 동일 이메일로 첫 번째 엔티티 저장
		Member firstMember = Member.createUser("dup@example.com", "hashed-password-60chars-xxxxxxxxxxxxxxxxx", "user1");
		jpaMemberRepository.saveAndFlush(firstMember);

		// When & Then — SQLErrorCodeSQLExceptionTranslator 빈 제거 후 MySQL에서 unique 위반은
		// DataIntegrityViolationException(cause=Hibernate ConstraintViolationException)으로 변환됨
		Member secondMember = Member.createUser("dup@example.com", "hashed-password-60chars-xxxxxxxxxxxxxxxxy", "user2");
		assertThatThrownBy(() -> jpaMemberRepository.saveAndFlush(secondMember))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasCauseInstanceOf(ConstraintViolationException.class);
	}
}
