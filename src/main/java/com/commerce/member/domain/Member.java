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
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "tbl_member", uniqueConstraints = {
	@UniqueConstraint(name = "uk_member_email", columnNames = {"email"})
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
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false)
	private MemberRole role;

	private Member(String email, String password, String username) {
		this.email = email;
		this.password = password;
		this.username = username;
		this.role = MemberRole.ROLE_USER; // default로 ROLE_USER
	}

	public static Member createUser(String email, String passwordHash, String username) {
		validateRequired(email, "Member email is required.");
		validateRequired(passwordHash, "Member password hash is required.");
		validateRequired(username, "Member username is required.");

		return new Member(email, passwordHash, username);
	}

	private static void validateRequired(String value, String message) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(message);
		}
	}

}
