package com.commerce.order.application.service.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentPg;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;

import com.commerce.order.domain.exception.OrderErrorCode;
import com.commerce.order.domain.exception.OrderException;
import com.commerce.member.domain.Member;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.outbox.stock.application.dto.StockRestoreOutboxCreateCommand;
import com.commerce.outbox.stock.application.service.StockRestoreOutboxCreateService;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.member.infrastructure.persistence.support.MemberPersistenceTestSupport;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;

@Tag("batch")
@SpringBatchTest
@SpringBootTest
@ActiveProfiles("test")
@Import({PersistenceCleanupTestSupport.class, MemberPersistenceTestSupport.class, ProductPersistenceTestSupport.class, OrderPersistenceTestSupport.class, PaymentPersistenceTestSupport.class})
class OrderExpirationBatchTest {

	@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
	@Autowired
	private JobLauncherTestUtils jobLauncherTestUtils;

	@Autowired
	private Job orderExpirationJob;

	@Autowired
	private PersistenceCleanupTestSupport persistenceCleanup;

	@Autowired
	private MemberPersistenceTestSupport memberPersistence;

	@Autowired
	private ProductPersistenceTestSupport productPersistence;

	@Autowired
	private OrderPersistenceTestSupport orderPersistence;

	@Autowired
	private PaymentPersistenceTestSupport paymentPersistence;

	@MockitoBean
	private StockRestoreOutboxCreateService stockRestoreOutboxCreateService;

	@BeforeEach
	void setUp() {
		jobLauncherTestUtils.setJob(orderExpirationJob);
	}

	@AfterEach
	void tearDown() {
		persistenceCleanup.deleteAllInBatch(
			paymentPersistence, memberPersistence, productPersistence, orderPersistence
		);
	}

	@DisplayName("만료된 주문은 배치에서 취소되고 outbox 이벤트가 생성된다")
	@Test
	void orderExpirationJob_whenOrderExpired_cancelOrder() throws Exception {
		// given
		LocalDateTime now = LocalDateTime.now();
		Member member = memberPersistence.save(createMember());
		Product product = productPersistence.save(createProduct());
		Order order = orderPersistence.save(createOrder(member, product));
		JobParameters parameters = jobParameters(now.plusMinutes(10));

		// when
		JobExecution execution = jobLauncherTestUtils.launchJob(parameters);

		// then
		assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		Order result = orderPersistence.findById(order.getId()).orElseThrow();
		assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELED);
		then(stockRestoreOutboxCreateService).should()
			.createOutboxEvent(argThat(command -> command.getOrderId().equals(result.getId())));
	}

	@DisplayName("만료 대상이 없으면 아무 것도 처리하지 않는다")
	@Test
	void orderExpirationJob_whenNoExpiredOrders_writeNothing() throws Exception {
		// given
		LocalDateTime now = LocalDateTime.now();
		Member member = memberPersistence.save(createMember());
		Product product = productPersistence.save(createProduct());
		orderPersistence.save(createOrder(member, product));
		JobParameters parameters = jobParameters(now.minusMinutes(60));

		// when
		JobExecution execution = jobLauncherTestUtils.launchJob(parameters);

		// then
		assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		StepExecution stepExecution = execution.getStepExecutions().iterator().next();
		assertThat(stepExecution.getReadCount()).isEqualTo(0);
		assertThat(stepExecution.getWriteCount()).isEqualTo(0);
		then(stockRestoreOutboxCreateService).should(never())
			.createOutboxEvent(any(StockRestoreOutboxCreateCommand.class));
	}

	@DisplayName("만료 기준을 지나지 않은 주문은 배치에서 유지된다")
	@Test
	void orderExpirationJob_whenOrderNotExpired_keepOrder() throws Exception {
		// given
		LocalDateTime now = LocalDateTime.now();
		Member member = memberPersistence.save(createMember());
		Product product = productPersistence.save(createProduct());
		Order order = orderPersistence.save(createOrder(member, product));
		JobParameters parameters = jobParameters(now.minusMinutes(60));

		// when
		JobExecution execution = jobLauncherTestUtils.launchJob(parameters);

		// then
		assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		Order result = orderPersistence.findById(order.getId()).orElseThrow();
		assertThat(result.getStatus()).isEqualTo(OrderStatus.INIT);
		then(stockRestoreOutboxCreateService).should(never())
			.createOutboxEvent(any(StockRestoreOutboxCreateCommand.class));
	}

	@DisplayName("만료 주문이 청크 사이즈를 넘어도 모두 취소 처리된다")
	@Test
	void orderExpirationJob_whenOrdersExceedChunk_processAllOrders() throws Exception {
		// given
		LocalDateTime now = LocalDateTime.now();
		Member member = memberPersistence.save(createMember());
		Product product = productPersistence.save(createProduct());
		int totalOrders = 120;
		List<Order> orders = new ArrayList<>();
		for (int i = 0; i < totalOrders; i++) {
			orders.add(orderPersistence.save(createOrder(member, product)));
		}
		JobParameters parameters = jobParameters(now.plusMinutes(10));

		// when
		JobExecution execution = jobLauncherTestUtils.launchJob(parameters);

		// then
		assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		StepExecution stepExecution = execution.getStepExecutions().iterator().next();
		assertThat(stepExecution.getReadCount()).isEqualTo(totalOrders);
		assertThat(stepExecution.getWriteCount()).isEqualTo(totalOrders);

		List<Long> orderIds = orders.stream().map(Order::getId).toList();
		List<Order> updatedOrders = orderPersistence.findAllById(orderIds);
		assertThat(updatedOrders).allMatch(order -> order.getStatus() == OrderStatus.CANCELED);
		then(stockRestoreOutboxCreateService).should(times(totalOrders))
			.createOutboxEvent(any(StockRestoreOutboxCreateCommand.class));
	}

	@DisplayName("같은 조건으로 배치를 다시 실행해도 추가 처리하지 않는다")
	@Test
	void orderExpirationJob_whenRunTwice_doNothingOnSecondRun() throws Exception {
		// given
		LocalDateTime now = LocalDateTime.now();
		Member member = memberPersistence.save(createMember());
		Product product = productPersistence.save(createProduct());
		Order order = orderPersistence.save(createOrder(member, product));
		orderPersistence.save(createOrder(member, product, 2));

		JobParameters firstParameters = jobParameters(now.plusMinutes(10));
		JobParameters secondParameters = jobParameters(now.plusMinutes(10));

		// when
		JobExecution firstExecution = jobLauncherTestUtils.launchJob(firstParameters);
		JobExecution secondExecution = jobLauncherTestUtils.launchJob(secondParameters);

		// then
		assertThat(firstExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThat(secondExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

		Order result = orderPersistence.findById(order.getId()).orElseThrow();
		assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELED);

		StepExecution secondStep = secondExecution.getStepExecutions().iterator().next();
		assertThat(secondStep.getReadCount()).isEqualTo(0);
		assertThat(secondStep.getWriteCount()).isEqualTo(0);
		then(stockRestoreOutboxCreateService).should(times(2))
			.createOutboxEvent(any(StockRestoreOutboxCreateCommand.class));
	}

	@DisplayName("주문 만료 처리 중 CustomException이 발생하면 해당 주문을 skip한다")
	@Test
	void orderExpirationJob_whenCustomExceptionOccurs_skipItem() throws Exception {
		// given
		LocalDateTime now = LocalDateTime.now();
		Member member = memberPersistence.save(createMember());
		Product product = productPersistence.save(createProduct());
		Order order = orderPersistence.save(createOrder(member, product));
		doThrow(new OrderException(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED))
			.when(stockRestoreOutboxCreateService)
			.createOutboxEvent(any(StockRestoreOutboxCreateCommand.class));
		JobParameters parameters = jobParameters(now.plusMinutes(10));

		// when
		JobExecution execution = jobLauncherTestUtils.launchJob(parameters);

		// then
		assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		StepExecution stepExecution = execution.getStepExecutions().iterator().next();
		assertThat(stepExecution.getWriteSkipCount()).isGreaterThanOrEqualTo(1);
		assertThat(stepExecution.getRollbackCount()).isGreaterThan(0);
		Order result = orderPersistence.findById(order.getId()).orElseThrow();
		assertThat(result.getStatus()).isEqualTo(OrderStatus.INIT);
	}

	@DisplayName("UNKNOWN 결제가 걸린 INIT 주문은 만료 배치에서 제외된다")
	@Test
	void orderExpirationJob_whenOrderHasUnknownPayment_skipExpiration() throws Exception {
		// given
		LocalDateTime now = LocalDateTime.now();
		Member member = memberPersistence.save(createMember());
		Product product = productPersistence.save(createProduct());
		Order order = orderPersistence.save(createOrder(member, product));

		paymentPersistence.save(unknownPayment(order.getId(), member.getId(), "1", now));

		JobParameters parameters = jobParameters(now.plusMinutes(10));

		// when
		JobExecution execution = jobLauncherTestUtils.launchJob(parameters);

		// then
		assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		StepExecution stepExecution = execution.getStepExecutions().iterator().next();
		assertThat(stepExecution.getReadCount()).isEqualTo(0);
		assertThat(stepExecution.getWriteCount()).isEqualTo(0);
		Order result = orderPersistence.findById(order.getId()).orElseThrow();
		assertThat(result.getStatus()).isEqualTo(OrderStatus.INIT);
		then(stockRestoreOutboxCreateService).should(never()).createOutboxEvent(any());
	}

	@DisplayName("UNKNOWN 결제 주문은 제외되고 일반 만료 주문은 정상 처리된다")
	@Test
	void orderExpirationJob_whenMixedOrders_processOnlyNonBlockedOrders() throws Exception {
		// given
		LocalDateTime now = LocalDateTime.now();
		Member member = memberPersistence.save(createMember());
		Product product = productPersistence.save(createProduct());

		Order blockedOrder = orderPersistence.save(createOrder(member, product));
		Order normalOrder = orderPersistence.save(createOrder(member, product));

		paymentPersistence.save(unknownPayment(blockedOrder.getId(), member.getId(), "2", now));

		JobParameters parameters = jobParameters(now.plusMinutes(10));

		// when
		JobExecution execution = jobLauncherTestUtils.launchJob(parameters);

		// then
		assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		StepExecution stepExecution = execution.getStepExecutions().iterator().next();
		assertThat(stepExecution.getReadCount()).isEqualTo(1);
		assertThat(stepExecution.getWriteCount()).isEqualTo(1);
		assertThat(orderPersistence.getOrderStatusById(blockedOrder.getId())).isEqualTo(OrderStatus.INIT);
		assertThat(orderPersistence.getOrderStatusById(normalOrder.getId())).isEqualTo(OrderStatus.CANCELED);
	}

	@DisplayName("cutoff 파라미터가 없으면 배치 실행이 실패한다")
	@Test
	void orderExpirationJob_whenCutoffMissing_failJob() throws Exception {
		// given
		JobParameters parameters = new JobParametersBuilder()
			.addLong("run.id", System.nanoTime())
			.toJobParameters();

		// when
		JobExecution execution = jobLauncherTestUtils.launchJob(parameters);

		// then
		assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
		StepExecution stepExecution = execution.getStepExecutions().iterator().next();
		assertThat(stepExecution.getExitStatus().getExitDescription())
			.contains("Non-skippable exception during read");
	}

	private JobParameters jobParameters(LocalDateTime cutoff) {
		return new JobParametersBuilder()
			.addString("cutoff", cutoff.toString())
			.addLong("run.id", System.nanoTime())
			.toJobParameters();
	}

	private Member createMember() {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		return Member.createUser("batch-expire-" + suffix + "@example.com", "password123", "u" + suffix);
	}

	private Product createProduct() {
		return Product.create("batch-product", 1000, null, null, ProductStatus.ON_SALE);
	}

	private Order createOrder(Member member, Product product) {
		return createOrder(member, product, 1);
	}

	private Order createOrder(Member member, Product product, int quantity) {
		Order order = Order.create(member.getId());
		order.addOrderItem(product.getId(), quantity, product.getPrice());
		return order;
	}

	// 만료 배치가 건너뛰는 기준은 결제가 활성 슬롯을 쥐고 있는지다. 결과를 모르는 결제가 그 대표 경우다.
	private Payment unknownPayment(Long orderId, Long memberId, String suffix, LocalDateTime now) {
		Payment payment = Payment.start(orderId, memberId, PaymentPg.NAVERPAY,
			"PK-batch-" + suffix, "IDEM-batch-" + suffix, 1000);
		payment.markInProgress("pg-unknown-" + suffix, now);
		payment.markUnknown();
		return payment;
	}

}
