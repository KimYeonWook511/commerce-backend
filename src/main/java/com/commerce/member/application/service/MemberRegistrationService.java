package com.commerce.member.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.member.application.dto.MemberRegistrationCommand;
import com.commerce.member.domain.Member;
import com.commerce.member.domain.repository.MemberRepository;
import com.commerce.member.domain.exception.MemberErrorCode;
import com.commerce.member.domain.exception.MemberException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemberRegistrationService {

	private final MemberRepository memberRepository;

	@Transactional
	public Member register(MemberRegistrationCommand command) {
		if (memberRepository.existsByEmail(command.getEmail())) {
			throw new MemberException(MemberErrorCode.DUPLICATE_EMAIL);
		}

		Member member = Member.createUser(command.getEmail(), command.getPasswordHash(), command.getUsername());

		Member savedMember = memberRepository.save(member);
		log.info("회원 등록 완료 memberId={}", savedMember.getId());
		return savedMember;
	}
}
