package com.commerce.member.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.commerce.member.application.command.MemberRegistrationCommand;
import com.commerce.member.domain.Member;
import com.commerce.member.domain.repository.MemberRepository;
import com.commerce.member.domain.exception.MemberErrorCode;
import com.commerce.member.domain.exception.MemberException;

@ExtendWith(MockitoExtension.class)
class MemberRegistrationServiceTest {

	@Mock
	private MemberRepository memberRepository;

	@InjectMocks
	private MemberRegistrationService memberRegistrationService;

	@DisplayName("회원 등록 시 이메일 중복을 확인하고 회원을 저장한다")
	@Test
	void register_whenEmailNotDuplicated_saveMember() {
		// given
		MemberRegistrationCommand command = MemberRegistrationCommand.builder()
			.email("test@example.com")
			.passwordHash("hashed-password")
			.username("user1")
			.build();

		given(memberRepository.existsByEmail("test@example.com")).willReturn(false);
		given(memberRepository.save(any(Member.class))).willAnswer(invocation -> invocation.getArgument(0));

		// when
		Member result = memberRegistrationService.register(command);

		// then
		ArgumentCaptor<Member> memberCaptor = ArgumentCaptor.forClass(Member.class);
		then(memberRepository).should().save(memberCaptor.capture());
		assertThat(memberCaptor.getValue().getEmail()).isEqualTo("test@example.com");
		assertThat(memberCaptor.getValue().getPassword()).isEqualTo("hashed-password");
		assertThat(memberCaptor.getValue().getUsername()).isEqualTo("user1");
		assertThat(result).isSameAs(memberCaptor.getValue());
	}

	@DisplayName("회원 등록 시 이메일이 중복되면 예외가 발생한다")
	@Test
	void register_whenEmailDuplicated_throwException() {
		// given
		MemberRegistrationCommand command = MemberRegistrationCommand.builder()
			.email("test@example.com")
			.passwordHash("hashed-password")
			.username("user1")
			.build();

		given(memberRepository.existsByEmail("test@example.com")).willReturn(true);

		// when & then
		assertThatThrownBy(() -> memberRegistrationService.register(command))
			.isInstanceOf(MemberException.class)
			.satisfies(exception -> {
				MemberException memberException = (MemberException) exception;
				assertThat(memberException.getErrorCode()).isEqualTo(MemberErrorCode.DUPLICATE_EMAIL);
			});
	}
}
