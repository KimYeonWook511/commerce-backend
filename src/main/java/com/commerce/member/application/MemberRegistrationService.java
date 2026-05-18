package com.commerce.member.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.member.application.command.MemberRegistrationCommand;
import com.commerce.member.domain.Member;
import com.commerce.member.domain.repository.MemberRepository;
import com.commerce.member.exception.MemberErrorCode;
import com.commerce.member.exception.MemberException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberRegistrationService {

	private final MemberRepository memberRepository;

	@Transactional
	public Member register(MemberRegistrationCommand command) {
		if (memberRepository.existsByEmail(command.getEmail())) {
			throw new MemberException(MemberErrorCode.DUPLICATE_EMAIL);
		}

		Member member = Member.createUser(command.getEmail(), command.getPasswordHash(), command.getUsername());

		return memberRepository.save(member);
	}
}
