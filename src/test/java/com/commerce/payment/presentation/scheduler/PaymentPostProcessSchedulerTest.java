package com.commerce.payment.presentation.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class PaymentPostProcessSchedulerTest {

	@DisplayName("주기 실행 메서드는 빠짐없이 결제 후처리 전용 풀을 가리킨다")
	@Test
	void scheduledMethods_whenDeclared_targetPaymentPostProcessPool() {
		List<Method> scheduled = Arrays.stream(PaymentPostProcessScheduler.class.getDeclaredMethods())
			.filter(method -> method.isAnnotationPresent(Scheduled.class))
			.toList();

		assertThat(scheduled).isNotEmpty();
		assertThat(scheduled).allSatisfy(method ->
			assertThat(method.getAnnotation(Scheduled.class).scheduler())
				// 빠뜨린 메서드는 공용 풀에서 돌아 결제사 지연이 주문 만료·재고 복구로 번지는데,
				// 실행 중에는 아무 증상이 없어 이 검사가 아니면 드러나지 않는다.
				.as("%s 가 전용 풀을 가리키지 않는다", method.getName())
				.isEqualTo(PaymentSchedulerConfig.SCHEDULER_BEAN));
	}
}
