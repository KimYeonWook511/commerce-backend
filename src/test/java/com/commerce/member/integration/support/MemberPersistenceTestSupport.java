package com.commerce.member.integration.support;

import org.springframework.boot.test.context.TestComponent;

import com.commerce.member.domain.Member;
import com.commerce.member.infrastructure.JpaMemberRepository;
import com.commerce.test.support.CleanupOrder;
import com.commerce.test.support.PersistenceTestSupport;

import lombok.RequiredArgsConstructor;

@TestComponent
@RequiredArgsConstructor
public class MemberPersistenceTestSupport implements PersistenceTestSupport {

	private final JpaMemberRepository memberRepository;

	@Override
	public CleanupOrder cleanupOrder() {
		return CleanupOrder.MEMBER;
	}

	@Override
	public void deleteAllInBatch() {
		memberRepository.deleteAllInBatch();
	}

	public Member save(Member member) {
		return memberRepository.save(member);
	}
}
