package com.commerce.member.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import com.commerce.member.domain.Member;

@DataJpaTest
@ActiveProfiles("test")
class MemberRepositoryTest {

	@Autowired
	private MemberRepository memberRepository;

	@DisplayName("이메일로 회원을 조회하면 해당 회원을 반환한다")
	@Test
	void findByEmail_whenMemberExists_returnMember() {
		// given
		Member member = Member.builder()
			.email("test@example.com")
			.password("hashed-pass")
			.username("user1")
			.build();
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
		Member member = Member.builder()
			.email("exists@example.com")
			.password("hashed-pass")
			.username("user2")
			.build();
		memberRepository.save(member);

		// when
		boolean exists = memberRepository.existsByEmail("exists@example.com");

		// then
		assertThat(exists).isTrue();
	}
}
