package com.commerce.member.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.commerce.member.domain.Member;
import com.commerce.member.domain.repository.MemberRepository;
import com.commerce.member.domain.exception.MemberErrorCode;
import com.commerce.member.domain.exception.MemberException;

@ExtendWith(MockitoExtension.class)
class FindMemberServiceTest {

	@Mock
	private MemberRepository memberRepository;

	@InjectMocks
	private FindMemberService findMemberService;

	@DisplayName("회원 id로 조회하면 해당 회원을 반환한다")
	@Test
	void findById_whenMemberExists_returnMember() {
		// given
		Member member = createMember("test@example.com", "user1");
		given(memberRepository.findById(1L)).willReturn(Optional.of(member));

		// when
		Optional<Member> result = findMemberService.findById(1L);

		// then
		assertThat(result).contains(member);
	}

	@DisplayName("이메일로 조회하면 해당 회원을 반환한다")
	@Test
	void findByEmail_whenMemberExists_returnMember() {
		// given
		Member member = createMember("test@example.com", "user1");
		given(memberRepository.findByEmail("test@example.com")).willReturn(Optional.of(member));

		// when
		Optional<Member> result = findMemberService.findByEmail("test@example.com");

		// then
		assertThat(result).contains(member);
	}

	@DisplayName("회원 id로 필수 조회 시 회원이 없으면 예외가 발생한다")
	@Test
	void getById_whenMemberNotFound_throwException() {
		// given
		given(memberRepository.findById(1L)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> findMemberService.getById(1L))
			.isInstanceOf(MemberException.class)
			.satisfies(exception -> {
				MemberException memberException = (MemberException) exception;
				assertThat(memberException.getErrorCode()).isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);
			});
	}

	private Member createMember(String email, String username) {
		return Member.createUser(email, "hashed-password", username);
	}
}
