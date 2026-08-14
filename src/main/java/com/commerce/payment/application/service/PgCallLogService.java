package com.commerce.payment.application.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.commerce.payment.application.port.dto.PgCallRecord;
import com.commerce.payment.domain.PgCallLog;
import com.commerce.payment.domain.repository.PgCallLogRepository;

import lombok.RequiredArgsConstructor;

/**
 * 결제사를 부른 사실을 남기는 트랜잭션 단위작업.
 *
 * <p>독립 트랜잭션이다. 결제 쪽이 롤백돼도 "불렀다"는 사실은 남아야 하고, 그것이 이 기록의 존재
 * 이유다 — 응답을 못 받은 호출이 갔는지 안 갔는지를 나중에 되짚을 수 있는 유일한 근거다.
 *
 * <p>거래 행을 로드하거나 그 버전을 올리지 않는다. 순수한 기록 남기기가 낙관 락 충돌을 일으키면
 * 판정이 기록 때문에 흔들린다.
 */
@Service
@RequiredArgsConstructor
public class PgCallLogService {

	private final PgCallLogRepository pgCallLogRepository;

	/**
	 * 승인 호출 직전에 행을 만든다. 응답을 받은 뒤에 만들면 결과를 못 받았을 때 행이 아예 생기지 않아
	 * 요청이 갔는지를 영영 알 수 없다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public PgCallLog startApproveCall(Long paymentId, String pgIdempotencyKey, LocalDateTime requestedAt) {
		return pgCallLogRepository.save(PgCallLog.startApproveCall(paymentId, pgIdempotencyKey, requestedAt));
	}

	/**
	 * 그 호출의 결과를 한 번 채운다. 판정의 정본이 먼저 커밋된 뒤에 온다.
	 *
	 * <p>응답이 돌아오지 않은 호출은 응답 시각을 비운다. 부른 시각으로 채우면 "결과를 모른다"가 기록에서
	 * 사라져, 조사할 때 확정된 건과 구분되지 않는다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordResult(PgCallLog callLog, LocalDateTime completedAt, PgCallRecord result) {
		LocalDateTime respondedAt = result.errorType().responded() ? completedAt : null;
		callLog.recordResult(
			respondedAt, result.errorType(), result.resultCode(), result.httpStatus(), result.rawResponse());
		pgCallLogRepository.save(callLog);
	}
}
