package com.commerce.payment.presentation.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class PaymentPostProcessSchedulerTest {

	/** 대사 전용 풀에서 돌아야 하는 진입점 */
	private static final Set<String> RECONCILE_METHODS = Set.of("reconcile", "reconcileRefunds");

	@DisplayName("대사는 대사 전용 풀에서, 나머지 후처리는 후처리 풀에서 돈다")
	@Test
	void scheduledMethods_whenDeclared_targetTheirOwnPool() {
		assertThat(scheduledMethods()).allSatisfy(method -> {
			String expected = RECONCILE_METHODS.contains(method.getName())
				? PaymentSchedulerConfig.RECONCILE_SCHEDULER_BEAN
				: PaymentSchedulerConfig.SCHEDULER_BEAN;
			assertThat(method.getAnnotation(Scheduled.class).scheduler())
				// 대사가 나머지와 같은 풀에 있으면 밀린 대사가 스레드를 오래 잡아 환불 발송이 굶고,
				// 풀 지정을 빠뜨리면 공용 풀에서 돌아 결제사 지연이 주문 만료·재고 복구로 번진다.
				// 둘 다 실행 중에는 아무 증상이 없어 이 검사가 아니면 드러나지 않는다.
				.as("%s 가 가리키는 풀이 다르다", method.getName())
				.isEqualTo(expected);
		});
	}

	@DisplayName("대사 진입점을 빠짐없이 센다 — 새로 붙인 대사가 검사 밖으로 새지 않는다")
	@Test
	void reconcileMethods_whenDeclared_areAllAccountedFor() {
		List<String> declared = scheduledMethods().stream()
			.map(Method::getName)
			.filter(RECONCILE_METHODS::contains)
			.toList();

		assertThat(declared).containsExactlyInAnyOrderElementsOf(RECONCILE_METHODS);
	}

	private static List<Method> scheduledMethods() {
		List<Method> scheduled = Arrays.stream(PaymentPostProcessScheduler.class.getDeclaredMethods())
			.filter(method -> method.isAnnotationPresent(Scheduled.class))
			.toList();
		assertThat(scheduled).isNotEmpty();
		return scheduled;
	}
}
