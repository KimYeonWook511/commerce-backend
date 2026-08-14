package com.commerce.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.commerce.common.jpa.JpaConfig;
import com.commerce.payment.domain.PgCallLog;
import com.commerce.payment.domain.PgCallType;
import com.commerce.payment.domain.PgErrorType;
import com.commerce.payment.domain.repository.PgCallLogRepository;
import com.commerce.support.TestcontainersSupport;

/**
 * 호출 기록이 결제·환불 어느 aggregate도 거치지 않고 자기 리포지토리로 쌓이는지, 응답 원본이 잘리지
 * 않고 그대로 남는지 실제 DB로 확인한다.
 */
@Tag("docker")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({JpaConfig.class, PgCallLogRepositoryAdapter.class})
class PgCallLogRepositoryAdapterIntegrationTest {

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		TestcontainersSupport.registerMySql(registry);
	}

	@Autowired
	private PgCallLogRepository pgCallLogRepository;

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 1, 12, 0);

	@DisplayName("승인 호출 기록에는 환불 참조가 비고 환불 호출 기록에는 결제와 환불이 함께 담긴다")
	@Test
	void save_whenCallTypeDiffers_storesRefundReferenceOnlyForRefundCalls() {
		PgCallLog approveCall = pgCallLogRepository.save(
			PgCallLog.startApproveCall(11L, "PK-1-1", NOW));
		PgCallLog refundCall = pgCallLogRepository.save(
			PgCallLog.startRefundCall(11L, 22L, "RF-1-1", NOW));

		assertThat(approveCall.getCallType()).isEqualTo(PgCallType.APPROVE);
		assertThat(approveCall.getRefundId()).isNull();
		assertThat(refundCall.getCallType()).isEqualTo(PgCallType.REFUND);
		assertThat(refundCall.getPaymentId()).isEqualTo(11L);
		assertThat(refundCall.getRefundId()).isEqualTo(22L);
	}

	@DisplayName("응답을 못 받은 호출은 응답 시각이 비고 무슨 일이 있었는지는 실패 유형만이 말한다")
	@Test
	void recordResult_whenResponseWasNeverRead_leavesRespondedAtEmpty() {
		PgCallLog call = pgCallLogRepository.save(PgCallLog.startApproveCall(12L, "PK-2-1", NOW));

		call.recordResult(null, PgErrorType.TIMEOUT, null, null, null);
		PgCallLog saved = pgCallLogRepository.save(call);

		assertThat(saved.getRespondedAt()).isNull();
		assertThat(saved.getErrorType()).isEqualTo(PgErrorType.TIMEOUT);
	}

	@DisplayName("응답 원본이 잘리지 않고 그대로 남는다")
	@Test
	void recordResult_whenResponseIsLong_storesRawResponseAsIs() {
		String rawResponse = "{\"detail\":\"%s\"}".formatted("x".repeat(5_000));
		PgCallLog call = pgCallLogRepository.save(PgCallLog.startApproveCall(13L, "PK-3-1", NOW));

		call.recordResult(NOW.plusSeconds(1), PgErrorType.NONE, "Success", 200, rawResponse);
		PgCallLog saved = pgCallLogRepository.save(call);

		assertThat(saved.getRawResponse()).isEqualTo(rawResponse);
		assertThat(saved.getHttpStatus()).isEqualTo(200);
		assertThat(saved.getResultCode()).isEqualTo("Success");
	}

	@DisplayName("같은 멱등키로 다시 부른 호출도 새 행으로 쌓인다")
	@Test
	void save_whenSameIdempotencyKeyIsCalledAgain_appendsAnotherRow() {
		PgCallLog first = pgCallLogRepository.save(PgCallLog.startApproveCall(14L, "PK-4-1", NOW));
		PgCallLog second = pgCallLogRepository.save(
			PgCallLog.startApproveCall(14L, "PK-4-1", NOW.plusMinutes(7)));

		assertThat(first.getId()).isNotEqualTo(second.getId());
		assertThat(first.getPgIdempotencyKey()).isEqualTo(second.getPgIdempotencyKey());
	}
}
