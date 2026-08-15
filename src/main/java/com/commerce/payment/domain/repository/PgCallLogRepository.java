package com.commerce.payment.domain.repository;

import com.commerce.payment.domain.PgCallLog;

/**
 * 호출 기록은 aggregate 밖이라 자기 리포지토리로 저장한다. 판정에 쓰이지 않으므로 읽는 경로는 두지
 * 않고, 문의·조사 때 근거로만 본다.
 */
public interface PgCallLogRepository {

	PgCallLog save(PgCallLog pgCallLog);
}
