package com.commerce.order.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;
import com.commerce.member.domain.Member;
import com.commerce.member.repository.MemberRepository;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.repository.OrderRepository;
import com.commerce.product.domain.Product;
import com.commerce.product.repository.ProductRepository;
import com.commerce.stock.service.StockService;

@Tag("batch")
@SpringBatchTest
@SpringBootTest
@ActiveProfiles("test")
class OrderExpirationBatchTest {

	@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
	@Autowired
	private JobLauncherTestUtils jobLauncherTestUtils;

	@Autowired
	private Job orderExpirationJob;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private OrderRepository orderRepository;

	@MockitoBean
	private StockService stockService;

	@BeforeEach
	void setUp() {
		jobLauncherTestUtils.setJob(orderExpirationJob);
	}

	@DisplayName("만료된 주문은 배치에서 취소 처리된다")
	@Test
	void orderExpirationJob_whenOrderExpired_cancelOrder() throws Exception {
		// given
		LocalDateTime now = LocalDateTime.now();
		Order order = saveOrder();
		JobParameters parameters = jobParameters(now.plusMinutes(10));

		// when
		JobExecution execution = jobLauncherTestUtils.launchJob(parameters);

		// then
		assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		Order result = orderRepository.findById(order.getId()).orElseThrow();
		assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELED);
		Long productId = order.getOrderItems().getFirst().getProduct().getId();
		then(stockService).should().increaseWithPessimisticLock(productId, 2);
	}

	@DisplayName("만료 대상이 없으면 아무 것도 처리하지 않는다")
	@Test
	void orderExpirationJob_whenNoExpiredOrders_writeNothing() throws Exception {
		// given
		LocalDateTime now = LocalDateTime.now();
		saveOrder();
		JobParameters parameters = jobParameters(now.minusMinutes(60));

		// when
		JobExecution execution = jobLauncherTestUtils.launchJob(parameters);

		// then
		assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		StepExecution stepExecution = execution.getStepExecutions().iterator().next();
		assertThat(stepExecution.getReadCount()).isEqualTo(0);
		assertThat(stepExecution.getWriteCount()).isEqualTo(0);
		then(stockService).should(never()).increaseWithPessimisticLock(anyLong(), anyInt());
	}

	@DisplayName("만료 기준을 지나지 않은 주문은 배치에서 유지된다")
	@Test
	void orderExpirationJob_whenOrderNotExpired_keepOrder() throws Exception {
		// given
		LocalDateTime now = LocalDateTime.now();
		Order order = saveOrder();
		JobParameters parameters = jobParameters(now.minusMinutes(60));

		// when
		JobExecution execution = jobLauncherTestUtils.launchJob(parameters);

		// then
		assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		Order result = orderRepository.findById(order.getId()).orElseThrow();
		assertThat(result.getStatus()).isEqualTo(OrderStatus.INIT);
		Long productId = order.getOrderItems().getFirst().getProduct().getId();
		then(stockService).should(never()).increaseWithPessimisticLock(productId, 2);
	}

	@DisplayName("만료 주문이 청크 사이즈를 넘어도 모두 취소 처리된다")
	@Test
	void orderExpirationJob_whenOrdersExceedChunk_processAllOrders() throws Exception {
		// given
		LocalDateTime now = LocalDateTime.now();
		Member member = saveMember();
		Product product = saveProduct();
		int totalOrders = 120;
		List<Order> orders = saveOrders(member, product, totalOrders);
		JobParameters parameters = jobParameters(now.plusMinutes(10));

		// when
		JobExecution execution = jobLauncherTestUtils.launchJob(parameters);

		// then
		assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		StepExecution stepExecution = execution.getStepExecutions().iterator().next();
		assertThat(stepExecution.getReadCount()).isEqualTo(totalOrders);
		assertThat(stepExecution.getWriteCount()).isEqualTo(totalOrders);

		List<Long> orderIds = orders.stream().map(Order::getId).toList();
		List<Order> updatedOrders = orderRepository.findAllById(orderIds);
		assertThat(updatedOrders).allMatch(order -> order.getStatus() == OrderStatus.CANCELED);
		then(stockService).should(times(totalOrders)).increaseWithPessimisticLock(product.getId(), 2);
	}

	@DisplayName("같은 조건으로 배치를 다시 실행해도 추가 처리하지 않는다")
	@Test
	void orderExpirationJob_whenRunTwice_doNothingOnSecondRun() throws Exception {
		// given
		LocalDateTime now = LocalDateTime.now();
		Member member = saveMember();
		Product product = saveProduct();
		Order order = saveOrder(member, product);
		JobParameters firstParameters = jobParameters(now.plusMinutes(10));
		JobParameters secondParameters = jobParameters(now.plusMinutes(10));

		// when
		JobExecution firstExecution = jobLauncherTestUtils.launchJob(firstParameters);
		JobExecution secondExecution = jobLauncherTestUtils.launchJob(secondParameters);

		// then
		assertThat(firstExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThat(secondExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

		Order result = orderRepository.findById(order.getId()).orElseThrow();
		assertThat(result.getStatus()).isEqualTo(OrderStatus.CANCELED);

		StepExecution secondStep = secondExecution.getStepExecutions().iterator().next();
		assertThat(secondStep.getReadCount()).isEqualTo(0);
		assertThat(secondStep.getWriteCount()).isEqualTo(0);
		then(stockService).should(times(1)).increaseWithPessimisticLock(product.getId(), 2);
	}

	@DisplayName("주문 만료 처리 중 CustomException이 발생하면 해당 주문을 skip한다")
	@Test
	void orderExpirationJob_whenCustomExceptionOccurs_skipItem() throws Exception {
		// given
		LocalDateTime now = LocalDateTime.now();
		Order order = saveOrder();
		doThrow(new OrderException(OrderErrorCode.ORDER_CANCEL_NOT_ALLOWED))
			.when(stockService).increaseWithPessimisticLock(anyLong(), anyInt());
		JobParameters parameters = jobParameters(now.plusMinutes(10));

		// when
		JobExecution execution = jobLauncherTestUtils.launchJob(parameters);

		// then
		assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
		StepExecution stepExecution = execution.getStepExecutions().iterator().next();
		assertThat(stepExecution.getWriteSkipCount()).isGreaterThanOrEqualTo(1);
		assertThat(stepExecution.getRollbackCount()).isGreaterThan(0);
		Order result = orderRepository.findById(order.getId()).orElseThrow();
		assertThat(result.getStatus()).isEqualTo(OrderStatus.INIT);
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

	private Order saveOrder() {
		Member member = saveMember();
		Product product = saveProduct();
		return saveOrder(member, product);
	}

	private Order saveOrder(Member member, Product product) {
		Order order = Order.create(member);
		order.addOrderItem(product, 2);
		return orderRepository.save(order);
	}

	private List<Order> saveOrders(Member member, Product product, int count) {
		List<Order> orders = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			orders.add(saveOrder(member, product));
		}
		return orders;
	}

	private Member saveMember() {
		String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
		Member member = Member.builder()
			.email("batch-expire-" + uniqueSuffix + "@example.com")
			.password("password123")
			.username("u" + uniqueSuffix)
			.build();
		return memberRepository.save(member);
	}

	private Product saveProduct() {
		String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
		Product product = Product.builder()
			.name("batch-product-" + uniqueSuffix)
			.price(1000)
			.build();
		return productRepository.save(product);
	}

}
