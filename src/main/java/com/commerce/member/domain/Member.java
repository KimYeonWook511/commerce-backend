package com.commerce.member.domain;

import com.commerce.common.jpa.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(uniqueConstraints = {
	@UniqueConstraint(name = "uk_member_email", columnNames = "email")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
public class Member extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 50)
	private String email;

	@Column(nullable = false, length = 60) // BCrypt 해시는 항상 60자로 저장됨
	private String password;

	@Column(nullable = false, length = 12)
	private String username;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private MemberRole role;

	@Builder
	private Member(String email, String password, String username) {
		this.email = email;
		this.password = password;
		this.username = username;
		this.role = MemberRole.ROLE_USER; // default로 ROLE_USER
	}

}
