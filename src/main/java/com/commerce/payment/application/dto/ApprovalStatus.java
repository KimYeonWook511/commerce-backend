package com.commerce.payment.application.dto;

/**
 * 승인 응답 본문이 말하는 상태. 값이 하나인 것은 확정된 승인만 본문으로 나가기 때문이며, 이 자리를
 * 없애지 않는 것은 클라이언트가 읽는 응답 형태를 바꾸지 않기 위해서다.
 */
public enum ApprovalStatus {

	SUCCESS
}
