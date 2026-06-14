package com.commerce.member.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.commerce.member.domain.Member;
import com.commerce.member.infrastructure.persistence.MemberRepositoryAdapter;
import com.commerce.member.domain.repository.MemberRepository;

@DataJpaTest
@ActiveProfiles("test")
@Import(MemberRepositoryAdapter.class)
class MemberRepositoryJpaAdapterTest {

	@Autowired
	private MemberRepository memberRepository;

	@DisplayName("이메일로 회원을 조회하면 해당 회원을 반환한다")
	@Test
	void findByEmail_whenMemberExists_returnMember() {
		// given
		Member member = Member.createUser("test@example.com", "hashed-pass", "user1");
		memberRepository.save(member);

		// when
		Member result = memberRepository.findByEmail("test@example.com").orElseThrow();

		// then
		assertThat(result.getEmail()).isEqualTo("test@example.com");
	}

	@DisplayName("이메일로 존재 여부를 확인하면 결과를 반환한다")
	@Test
	void existsByEmail_whenMemberExists_returnTrue() {
		// given
		Member member = Member.createUser("exists@example.com", "hashed-pass", "user2");
		memberRepository.save(member);

		// when
		boolean exists = memberRepository.existsByEmail("exists@example.com");

		// then
		assertThat(exists).isTrue();
	}

	@DisplayName("이메일이 중복되면 저장 시점에 예외가 발생한다")
	@Test
	// H2와 MySQL 모두 DataIntegrityViolationException이 발생한다.
	// MySQL cause 체인 형태(Hibernate ConstraintViolationException 포함)는 UniqueViolationExceptionShapeTest(integrationTest)로 검증한다.
	void save_whenEmailDuplicated_throwDataIntegrityViolationException() {
		// given
		Member firstMember = Member.createUser("duplicate@example.com", "hashed-pass", "user1");
		Member secondMember = Member.createUser("duplicate@example.com", "hashed-pass", "user2");
		memberRepository.save(firstMember);

		// when & then
		assertThatThrownBy(() -> memberRepository.save(secondMember))
			.isInstanceOf(DataIntegrityViolationException.class);
	}
}
