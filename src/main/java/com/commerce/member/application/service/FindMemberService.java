package com.commerce.member.application.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.member.domain.Member;
import com.commerce.member.domain.repository.MemberRepository;
import com.commerce.member.domain.exception.MemberErrorCode;
import com.commerce.member.domain.exception.MemberException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FindMemberService {

	private final MemberRepository memberRepository;

	@Transactional(readOnly = true)
	public Optional<Member> findById(Long memberId) {
		return memberRepository.findById(memberId);
	}

	@Transactional(readOnly = true)
	public Optional<Member> findByEmail(String email) {
		return memberRepository.findByEmail(email);
	}

	@Transactional(readOnly = true)
	public Member getById(Long memberId) {
		return memberRepository.findById(memberId)
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));
	}
}
