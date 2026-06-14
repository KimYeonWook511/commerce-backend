package com.commerce.member.domain.exception;

import com.commerce.common.exception.CustomException;
import com.commerce.common.exception.ErrorCode;

public class MemberException extends CustomException {

	public MemberException(ErrorCode errorCode) {
		super(errorCode);
	}
}
