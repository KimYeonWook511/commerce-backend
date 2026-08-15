package com.commerce.payment.application.port;

import com.commerce.payment.application.port.dto.PgApproveResult;
import com.commerce.payment.application.port.dto.PgCallSource;
import com.commerce.payment.application.port.dto.PgHistoryResult;
import com.commerce.payment.application.port.dto.PgHistoryScope;
import com.commerce.payment.application.port.dto.PgRefundResult;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.Refund;

/**
 * 결제사에 요청을 보내고 이력을 읽는 유일한 통로. 이름에 결제사가 없고, 결제사 하나가 구현체 하나로
 * 붙고 떨어진다.
 *
 * <p>넘기는 값은 우리가 들고 있는 결제 행이다. 결제사마다 요구하는 인자가 다르지만 그 값들이 전부 그
 * 행에 있어, 구현체가 필요한 것만 꺼내 쓰면 이 인터페이스가 결제사 사정을 알 필요가 없다.
 *
 * <p>구현체는 예외를 던지지 않는다. 타임아웃도 서버 오류도 모르는 응답도 전부 정해진 갈래로 접어
 * 돌려주므로, 부르는 쪽이 "예외를 놓치면 무슨 일이 나는가"를 따지지 않아도 된다.
 */
public interface PaymentGatewayPort {

	/** 승인을 요청한다 */
	PgApproveResult approve(Payment payment);

	/**
	 * 환불을 요청한다. 어느 자리에서 부르는지를 함께 받는 것은 읽기 제한 시간이 진입점마다 다르기
	 * 때문이며, 그 값을 고르는 것은 결제사 사정을 아는 어댑터의 일이다.
	 */
	PgRefundResult refund(Payment payment, Refund refund, PgCallSource source);

	/** 이력을 읽는다. 여러 페이지에 걸쳐 있으면 끝까지 읽는다 */
	PgHistoryResult readHistory(Payment payment, PgHistoryScope scope);
}
