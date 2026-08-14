package com.commerce.payment.application.config;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.commerce.payment.domain.policy.PaymentPostProcessPolicy;

/**
 * 도메인 정책을 빈으로 등록하는 자리. 운영 설정에서 온 값을 읽어 정책에 넘긴다.
 *
 * <p>정책 클래스에 등록 애노테이션을 붙이지 않는 이유가 여기 있다. 붙이면 설정값을 읽는 일까지
 * 도메인이 하게 되어, 값을 바꿀 때마다 도메인을 건드린다.
 *
 * <p>이 자리를 다른 용도로 넓히지 않는다. 도메인 정책을 등록하는 것까지이고, 흐름 조립이나 트랜잭션이
 * 들어오면 {@code usecase}·{@code service}와 책임이 겹친다.
 */
@Configuration
public class PaymentPolicyConfig {

	@Bean
	public PaymentPostProcessPolicy paymentPostProcessPolicy(
		@Value("${payment.postprocess.reconcile.grace:30s}") Duration reconcileGrace,
		@Value("${payment.postprocess.reconcile.intervals:10s,30s,2m,5m,10m}") List<Duration> reconcileIntervals,
		@Value("${payment.postprocess.notify.escalation:1h}") Duration notifyEscalation,
		@Value("${payment.postprocess.notify.interval:1h}") Duration notifyInterval,
		@Value("${payment.postprocess.expire.threshold:1h}") Duration expireThreshold
	) {
		return new PaymentPostProcessPolicy(
			reconcileGrace, reconcileIntervals, notifyEscalation, notifyInterval, expireThreshold);
	}
}
