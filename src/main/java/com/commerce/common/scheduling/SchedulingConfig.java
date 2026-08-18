package com.commerce.common.scheduling;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/**
 * 자기 풀을 따로 지정하지 않은 주기 실행이 쓰는 공용 스레드 풀.
 *
 * <p>스프링이 만들어 주던 것을 여기서 직접 만든다. 자동 설정은 이 타입의 빈이 하나라도 있으면 물러나므로,
 * 어느 도메인이 전용 풀을 갖는 순간 공용 풀이 사라진다. 그러면 남은 하나가 그 도메인의 전용 풀이 되어
 * 나머지 주기 실행이 전부 그리로 흘러들고, 갈라놓은 것이 도로 합쳐진다.
 *
 * <p>이름을 기본값으로 둔다. 풀이 여럿일 때 자기 풀을 지정하지 않은 것들이 어디로 갈지를 스프링이 이
 * 이름으로 고르며, 이름이 어긋나면 어느 것이 뽑힐지 정해지지 않는다.
 *
 * <p>크기와 이름 접두사는 {@code spring.task.scheduling}이 정한다. 빌더를 거치는 것이 그래서다 —
 * 직접 만들면 그 설정이 이 풀에 닿지 않는다.
 */
@Configuration
public class SchedulingConfig {

	@Bean(ScheduledAnnotationBeanPostProcessor.DEFAULT_TASK_SCHEDULER_BEAN_NAME)
	public TaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
		return builder.build();
	}
}
