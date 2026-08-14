package com.commerce.payment.application.port.dto;

import com.commerce.payment.domain.PgErrorType;

/**
 * 호출 기록 행을 채울 값. 어댑터가 접은 갈래와 따로 나른다 — 기록을 쓰는 것은 어댑터가 아니라 응용
 * 계층이라, 어댑터가 갈래만 넘기고 원본을 버리면 그 칸들이 영영 빈 채 남는다.
 *
 * <p>응답 원본은 뜻을 해석하지 않은 문자열 그대로다. 여기서 결제사 타입으로 옮기면 그 어휘가 응용
 * 계층까지 따라 올라온다.
 *
 * @param errorType  그 호출에 무슨 일이 있었나. 응답을 못 받은 이유는 원본으로 역산할 수 없어 따로 담는다
 * @param resultCode 결제사가 준 결과 코드. 응답을 못 받았으면 없다
 * @param httpStatus 응답의 HTTP 상태. 중복 요청을 뜻하는 상태를 나중에 가려낼 수 있게 남긴다
 * @param rawResponse 응답 본문 그대로. 뽑아낸 값만 담으면 나중에 무슨 일이 있었는지 되짚을 수 없다
 */
public record PgCallRecord(
	PgErrorType errorType,
	String resultCode,
	Integer httpStatus,
	String rawResponse
) {
}
