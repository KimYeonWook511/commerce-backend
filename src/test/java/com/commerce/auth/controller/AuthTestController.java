package com.commerce.auth.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.commerce.auth.interceptor.RequireRole;
import com.commerce.auth.resolver.AuthenticatedMemberId;
import com.commerce.member.domain.MemberRole;

@RestController
@RequestMapping("/test")
class AuthTestController {

	@GetMapping("/secure")
	public ResponseEntity<Void> secure() {
		return ResponseEntity.ok().build();
	}

	@RequireRole(MemberRole.ROLE_ADMIN)
	@GetMapping("/admin")
	public ResponseEntity<Void> admin(
	) {
		return ResponseEntity.ok().build();
	}

	@GetMapping("/member-id")
	public ResponseEntity<String> memberId(
		@AuthenticatedMemberId Long memberId
	) {
		return ResponseEntity.ok(String.valueOf(memberId));
	}
}
