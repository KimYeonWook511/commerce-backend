package com.commerce.payment.naverpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

import com.commerce.member.domain.Member;
import com.commerce.member.repository.MemberRepository;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;
import com.commerce.order.infrastructure.JpaOrderRepository;
import com.commerce.orderitem.repository.OrderItemRepository;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentAttempt;
import com.commerce.payment.domain.PaymentAttemptFailCode;
import com.commerce.payment.domain.PaymentAttemptStatus;
import com.commerce.payment.domain.PaymentAttemptType;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
import com.commerce.payment.naverpay.client.NaverPayClient;
import com.commerce.payment.naverpay.client.response.NaverPayResponse;
import com.commerce.payment.naverpay.client.response.body.NaverPayApproveBody;
import com.commerce.payment.naverpay.client.response.body.NaverPayCancelBody;
import com.commerce.payment.naverpay.client.response.body.NaverPayHistoryBody;
import com.commerce.payment.naverpay.exception.NaverPayErrorCode;
import com.commerce.payment.naverpay.exception.NaverPayException;
import com.commerce.payment.naverpay.service.result.NaverPayApproveResult;
import com.commerce.payment.naverpay.service.result.NaverPayApproveStatus;
import com.commerce.payment.repository.PaymentAttemptRepository;
import com.commerce.payment.repository.PaymentRepository;
import com.commerce.payment.service.PaymentAttemptService;
import com.commerce.payment.service.PaymentService;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.JpaProductRepository;

@SpringBootTest
@ActiveProfiles("test")
class NaverPayServiceIntegrationTest {

	@Autowired
	private NaverPayService naverPayService;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private JpaProductRepository productRepository;

	@Autowired
	private JpaOrderRepository orderRepository;

	@Autowired
	private OrderItemRepository orderItemRepository;

	@Autowired
	private PaymentRepository paymentRepository;

	@Autowired
	private PaymentAttemptRepository paymentAttemptRepository;

	@MockitoBean
	private NaverPayClient naverPayClient;

	@MockitoSpyBean
	private PaymentService paymentService;

	@MockitoSpyBean
	private PaymentAttemptService paymentAttemptService;

	@AfterEach
	void tearDown() {
		Mockito.reset(naverPayClient, paymentService, paymentAttemptService);
		paymentAttemptRepository.deleteAllInBatch();
		paymentRepository.deleteAllInBatch();
		orderItemRepository.deleteAllInBatch();
		orderRepository.deleteAllInBatch();
		productRepository.deleteAllInBatch();
		memberRepository.deleteAllInBatch();
	}

	/**
	 * ===================================================
	 * 1. 정상 승인
	 * ===================================================
	 */
	@DisplayName("승인이 성공하면 payment를 생성하고 order를 PAID로 변경하며 approve attempt를 SUCCEEDED로 저장한다")
	@Test
	void approve_whenSuccess_createPaymentAndMarkOrderPaidAndSucceedAttempt() {
		// given
		Member member = createMember();
		createOrder(member, "PAY-INT-1", 1000);
		given(naverPayClient.approve("pg-int-1"))
			.willReturn(buildApprovalResponse("PAY-INT-1", 1000, "Success", "SUCCESS", "pg-int-1"));

		// when
		NaverPayApproveResult result = naverPayService.approve(member.getId(), "PAY-INT-1", "pg-int-1");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.SUCCESS);
		assertThat(result.getPgPaymentId()).isEqualTo("pg-int-1");
		assertThat(paymentRepository.findByMerchantPayKey("PAY-INT-1")).isPresent();
		assertThat(orderRepository.findByMerchantPayKey("PAY-INT-1").orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(getAttempt("PAY-INT-1", "pg-int-1", PaymentAttemptType.APPROVE).getStatus())
			.isEqualTo(PaymentAttemptStatus.SUCCEEDED);
	}

	/**
	 * ===================================================
	 * 2. 이미 처리 중/완료 응답
	 * ===================================================
	 */
	@DisplayName("AlreadyOnGoing 응답이면 PROCESSING을 반환하고 payment를 생성하지 않는다")
	@Test
	void approve_whenAlreadyOnGoing_returnProcessingWithoutPersistingPayment() {
		// given
		Member member = createMember();
		createOrder(member, "PAY-INT-2-1", 1000);
		given(naverPayClient.approve("pg-int-2-1"))
			.willReturn(buildApprovalResponse("PAY-INT-2-1", 1000, "AlreadyOnGoing", "SUCCESS", "pg-int-2-1"));

		// when
		NaverPayApproveResult result = naverPayService.approve(member.getId(), "PAY-INT-2-1", "pg-int-2-1");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.PROCESSING);
		assertThat(paymentRepository.findByMerchantPayKey("PAY-INT-2-1")).isEmpty();
		assertThat(getAttempt("PAY-INT-2-1", "pg-int-2-1", PaymentAttemptType.APPROVE).getStatus())
			.isEqualTo(PaymentAttemptStatus.REQUESTED);
	}

	@DisplayName("AlreadyComplete 응답이면 history를 조회해서 결제를 완료 처리한다")
	@Test
	void approve_whenAlreadyComplete_completeByHistory() {
		// given
		Member member = createMember();
		createOrder(member, "PAY-INT-2-2", 1000);
		given(naverPayClient.approve("pg-int-2-2"))
			.willReturn(buildApprovalResponse("PAY-INT-2-2", 1000, "AlreadyComplete", "SUCCESS", "pg-int-2-2"));
		given(naverPayClient.getAllHistory("pg-int-2-2"))
			.willReturn(buildHistoryResponse("PAY-INT-2-2", 1000, "SUCCESS", "01", "pg-int-2-2"));

		// when
		NaverPayApproveResult result = naverPayService.approve(member.getId(), "PAY-INT-2-2", "pg-int-2-2");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.SUCCESS);
		assertThat(paymentRepository.findByMerchantPayKey("PAY-INT-2-2")).isPresent();
		then(naverPayClient).should().getAllHistory("pg-int-2-2");
	}

	/**
	 * ===================================================
	 * 3. History 기반 반영 분기
	 * ===================================================
	 */
	@DisplayName("history code가 InvalidMerchant면 PAYMENT_INVALID_MERCHANT를 던진다")
	@Test
	void approve_whenAlreadyCompleteAndHistoryCodeInvalid_throwInvalidMerchant() {
		// given
		Member member = createMember();
		createOrder(member, "PAY-INT-3-1", 1000);
		given(naverPayClient.approve("pg-int-3-1"))
			.willReturn(buildApprovalResponse("PAY-INT-3-1", 1000, "AlreadyComplete", "SUCCESS", "pg-int-3-1"));
		given(naverPayClient.getAllHistory("pg-int-3-1"))
			.willReturn(buildHistoryResponseWithCode("PAY-INT-3-1", 1000, "SUCCESS", "01", "pg-int-3-1", "InvalidMerchant"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(member.getId(), "PAY-INT-3-1", "pg-int-3-1"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_INVALID_MERCHANT));
	}

	@DisplayName("history 최신 이력이 승인 완료가 아니면 PAYMENT_NOT_FOUND를 던진다")
	@Test
	void approve_whenAlreadyCompleteAndHistoryNotCompleted_throwNotFound() {
		// given
		Member member = createMember();
		createOrder(member, "PAY-INT-3-2", 1000);
		given(naverPayClient.approve("pg-int-3-2"))
			.willReturn(buildApprovalResponse("PAY-INT-3-2", 1000, "AlreadyComplete", "SUCCESS", "pg-int-3-2"));
		given(naverPayClient.getAllHistory("pg-int-3-2"))
			.willReturn(buildHistoryResponse("PAY-INT-3-2", 1000, "PENDING", "02", "pg-int-3-2"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(member.getId(), "PAY-INT-3-2", "pg-int-3-2"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND));
	}

	@DisplayName("AlreadyComplete 응답 이후 history가 취소 완료 상태면 approve attempt를 ALREADY_CANCELED로 실패 처리하고 PAYMENT_ALREADY_CANCELED를 던진다")
	@Test
	void approve_whenAlreadyCompleteAndHistoryCanceled_throwAlreadyCanceled() {
		// given
		Member member = createMember();
		createOrder(member, "PAY-INT-3-2-C", 1000);
		given(naverPayClient.approve("pg-int-3-2-c"))
			.willReturn(buildApprovalResponse("PAY-INT-3-2-C", 1000, "AlreadyComplete", "SUCCESS", "pg-int-3-2-c"));
		given(naverPayClient.getAllHistory("pg-int-3-2-c"))
			.willReturn(buildHistoryResponse("PAY-INT-3-2-C", 1000, "CANCELLED", "03", "pg-int-3-2-c"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(member.getId(), "PAY-INT-3-2-C", "pg-int-3-2-c"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_ALREADY_CANCELED));
		assertThat(getAttempt("PAY-INT-3-2-C", "pg-int-3-2-c", PaymentAttemptType.APPROVE).getStatus())
			.isEqualTo(PaymentAttemptStatus.FAILED);
		assertThat(getAttempt("PAY-INT-3-2-C", "pg-int-3-2-c", PaymentAttemptType.APPROVE).getFailCode())
			.isEqualTo(PaymentAttemptFailCode.ALREADY_CANCELED);
	}

	@DisplayName("history 목록이 비어있으면 PAYMENT_NOT_FOUND를 던진다")
	@Test
	void approve_whenAlreadyCompleteAndHistoryEmpty_throwNotFound() {
		// given
		Member member = createMember();
		createOrder(member, "PAY-INT-3-3", 1000);
		given(naverPayClient.approve("pg-int-3-3"))
			.willReturn(buildApprovalResponse("PAY-INT-3-3", 1000, "AlreadyComplete", "SUCCESS", "pg-int-3-3"));
		given(naverPayClient.getAllHistory("pg-int-3-3"))
			.willReturn(buildEmptyHistoryResponse());

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(member.getId(), "PAY-INT-3-3", "pg-int-3-3"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND));
	}

	/**
	 * ===================================================
	 * 4. merchantPayKey 불일치
	 * ===================================================
	 */
	@DisplayName("approve 응답 merchantPayKey가 다르면 approve attempt를 FAILED로 저장하고 취소는 요청하지 않는다")
	@Test
	void approve_whenApproveMerchantPayKeyMismatch_failAttemptWithoutCancel() {
		// given
		Member member = createMember();
		createOrder(member, "PAY-INT-4-1", 1000);
		given(naverPayClient.approve("pg-int-4-1"))
			.willReturn(buildApprovalResponse("OTHER-PAY", 1000, "Success", "SUCCESS", "pg-int-4-1"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(member.getId(), "PAY-INT-4-1", "pg-int-4-1"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_MERCHANT_KEY_MISMATCH));
		assertThat(getAttempt("PAY-INT-4-1", "pg-int-4-1", PaymentAttemptType.APPROVE).getStatus())
			.isEqualTo(PaymentAttemptStatus.FAILED);
		assertCancelAttemptEmpty("PAY-INT-4-1", "pg-int-4-1");
		then(naverPayClient).should(never()).cancel(any());
	}

	@DisplayName("history 경로 merchantPayKey가 다르면 approve attempt를 FAILED로 저장하고 취소는 요청하지 않는다")
	@Test
	void approve_whenHistoryMerchantPayKeyMismatch_failAttemptWithoutCancel() {
		// given
		Member member = createMember();
		createOrder(member, "PAY-INT-4-2", 1000);
		given(naverPayClient.approve("pg-int-4-2"))
			.willReturn(buildApprovalResponse("PAY-INT-4-2", 1000, "AlreadyComplete", "SUCCESS", "pg-int-4-2"));
		given(naverPayClient.getAllHistory("pg-int-4-2"))
			.willReturn(buildHistoryResponse("OTHER-PAY", 1000, "SUCCESS", "01", "pg-int-4-2"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(member.getId(), "PAY-INT-4-2", "pg-int-4-2"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND));
		assertThat(getAttempt("PAY-INT-4-2", "pg-int-4-2", PaymentAttemptType.APPROVE).getStatus())
			.isEqualTo(PaymentAttemptStatus.FAILED);
		assertCancelAttemptEmpty("PAY-INT-4-2", "pg-int-4-2");
		then(naverPayClient).should(never()).cancel(any());
	}

	/**
	 * ===================================================
	 * 5. 금액 불일치
	 * ===================================================
	 */
	@DisplayName("금액 불일치면 승인 실패 처리 후 취소 요청을 수행한다")
	@Test
	void approve_whenAmountMismatch_failApproveAndRequestCancel() {
		// given
		Member member = createMember();
		createOrder(member, "PAY-INT-5-1", 1000);
		given(naverPayClient.approve("pg-int-5-1"))
			.willReturn(buildApprovalResponse("PAY-INT-5-1", 2000, "Success", "SUCCESS", "pg-int-5-1"));
		given(naverPayClient.cancel(any()))
			.willReturn(buildCancelResponse("Success", "pg-int-5-1"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(member.getId(), "PAY-INT-5-1", "pg-int-5-1"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH));
		assertThat(getAttempt("PAY-INT-5-1", "pg-int-5-1", PaymentAttemptType.CANCEL).getStatus())
			.isEqualTo(PaymentAttemptStatus.SUCCEEDED);
	}

	@DisplayName("금액 불일치에서 cancel 응답이 AlreadyCanceled면 cancel attempt를 SUCCEEDED로 저장한다")
	@Test
	void approve_whenAmountMismatchAndCancelAlreadyCanceled_markCancelSucceeded() {
		// given
		Member member = createMember();
		createOrder(member, "PAY-INT-5-2", 1000);
		given(naverPayClient.approve("pg-int-5-2"))
			.willReturn(buildApprovalResponse("PAY-INT-5-2", 2000, "Success", "SUCCESS", "pg-int-5-2"));
		given(naverPayClient.cancel(any()))
			.willReturn(buildCancelResponse("AlreadyCanceled", "pg-int-5-2"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(member.getId(), "PAY-INT-5-2", "pg-int-5-2"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH));
		assertThat(getAttempt("PAY-INT-5-2", "pg-int-5-2", PaymentAttemptType.CANCEL).getStatus())
			.isEqualTo(PaymentAttemptStatus.SUCCEEDED);
	}

	@DisplayName("금액 불일치에서 cancel 응답이 AlreadyOnGoing이면 cancel attempt를 REQUESTED로 유지한다")
	@Test
	void approve_whenAmountMismatchAndCancelAlreadyOnGoing_keepCancelRequested() {
		// given
		Member member = createMember();
		createOrder(member, "PAY-INT-5-3", 1000);
		given(naverPayClient.approve("pg-int-5-3"))
			.willReturn(buildApprovalResponse("PAY-INT-5-3", 2000, "Success", "SUCCESS", "pg-int-5-3"));
		given(naverPayClient.cancel(any()))
			.willReturn(buildCancelResponse("AlreadyOnGoing", "pg-int-5-3"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(member.getId(), "PAY-INT-5-3", "pg-int-5-3"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH));
		assertThat(getAttempt("PAY-INT-5-3", "pg-int-5-3", PaymentAttemptType.CANCEL).getStatus())
			.isEqualTo(PaymentAttemptStatus.REQUESTED);
	}

	@DisplayName("금액 불일치에서 cancel 응답이 Fail이면 cancel attempt를 FAILED로 저장한다")
	@Test
	void approve_whenAmountMismatchAndCancelFail_markCancelFailed() {
		// given
		Member member = createMember();
		createOrder(member, "PAY-INT-5-4", 1000);
		given(naverPayClient.approve("pg-int-5-4"))
			.willReturn(buildApprovalResponse("PAY-INT-5-4", 2000, "Success", "SUCCESS", "pg-int-5-4"));
		given(naverPayClient.cancel(any()))
			.willReturn(buildCancelResponse("Fail", "pg-int-5-4"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(member.getId(), "PAY-INT-5-4", "pg-int-5-4"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH));
		assertThat(getAttempt("PAY-INT-5-4", "pg-int-5-4", PaymentAttemptType.CANCEL).getStatus())
			.isEqualTo(PaymentAttemptStatus.FAILED);
	}

	/**
	 * ===================================================
	 * 6. 중복 결제
	 * ===================================================
	 */
	@DisplayName("같은 merchantPayKey에 기존 결제가 있으면 중복 승인 시도여도 기존 결제를 반환한다")
	@Test
	void approve_whenPaymentAlreadyExists_returnExistingPayment() {
		// given
		Member member = createMember();
		Order order = createOrder(member, "PAY-INT-6-1", 1000);
		paymentRepository.saveAndFlush(
			Payment.createCompleted(order, PaymentProvider.NAVERPAY, "PAY-INT-6-1", "pg-existing", LocalDateTime.now())
		);

		// when
		NaverPayApproveResult result = naverPayService.approve(member.getId(), "PAY-INT-6-1", "pg-int-6-1");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.SUCCESS);
		assertThat(result.getPgPaymentId()).isEqualTo("pg-existing");
		then(naverPayClient).should(never()).approve(any());
	}

	@DisplayName("completeApprove에서 PAYMENT_DUPLICATE가 발생하면 현재 승인건 취소를 요청한다")
	@Test
	void approve_whenCompleteApproveThrowsDuplicate_cancelApprovedPayment() {
		// given
		Member member = createMember();
		createOrder(member, "PAY-INT-6-2", 1000);
		given(naverPayClient.approve("pg-int-6-2"))
			.willReturn(buildApprovalResponse("PAY-INT-6-2", 1000, "Success", "SUCCESS", "pg-int-6-2"));
		given(naverPayClient.cancel(any()))
			.willReturn(buildCancelResponse("Success", "pg-int-6-2"));
		Mockito.doThrow(new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE))
			.when(paymentService)
			.completeApprove(eq("PAY-INT-6-2"), eq(PaymentProvider.NAVERPAY), eq("pg-int-6-2"), any(LocalDateTime.class));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(member.getId(), "PAY-INT-6-2", "pg-int-6-2"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_DUPLICATE));
		assertThat(getAttempt("PAY-INT-6-2", "pg-int-6-2", PaymentAttemptType.APPROVE).getStatus())
			.isEqualTo(PaymentAttemptStatus.FAILED);
		assertThat(getAttempt("PAY-INT-6-2", "pg-int-6-2", PaymentAttemptType.APPROVE).getFailCode())
			.isEqualTo(PaymentAttemptFailCode.DUPLICATE_PAYMENT);
		assertThat(getAttempt("PAY-INT-6-2", "pg-int-6-2", PaymentAttemptType.CANCEL).getStatus())
			.isEqualTo(PaymentAttemptStatus.SUCCEEDED);
	}

	@DisplayName("이미 취소 완료된 시도가 있으면 completeApprove 중복 예외에서도 취소를 다시 요청하지 않는다")
	@Test
	void approve_whenCompleteApproveThrowsDuplicateAndCancelAlreadySucceeded_skipCancelRequest() {
		// given
		Member member = createMember();
		createOrder(member, "PAY-INT-6-3", 1000);
		PaymentAttempt cancelAttempt = PaymentAttempt.createCancelRequested(
			"PAY-INT-6-3", "pg-int-6-3", 1000, PaymentProvider.NAVERPAY
		);
		cancelAttempt.cancelSucceed(LocalDateTime.now());
		paymentAttemptRepository.saveAndFlush(cancelAttempt);

		given(naverPayClient.approve("pg-int-6-3"))
			.willReturn(buildApprovalResponse("PAY-INT-6-3", 1000, "Success", "SUCCESS", "pg-int-6-3"));
		Mockito.doThrow(new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE))
			.when(paymentService)
			.completeApprove(eq("PAY-INT-6-3"), eq(PaymentProvider.NAVERPAY), eq("pg-int-6-3"), any(LocalDateTime.class));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(member.getId(), "PAY-INT-6-3", "pg-int-6-3"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_DUPLICATE));
		assertThat(getAttempt("PAY-INT-6-3", "pg-int-6-3", PaymentAttemptType.CANCEL).getStatus())
			.isEqualTo(PaymentAttemptStatus.SUCCEEDED);
		then(naverPayClient).should(never()).cancel(any());
	}

	@DisplayName("completeApprove에서 PAYMENT_DUPLICATE가 발생하고 취소가 실패하면 cancel attempt를 FAILED로 저장한다")
	@Test
	void approve_whenCompleteApproveThrowsDuplicateAndCancelFail_markCancelFailed() {
		// given
		Member member = createMember();
		createOrder(member, "PAY-INT-6-4", 1000);
		given(naverPayClient.approve("pg-int-6-4"))
			.willReturn(buildApprovalResponse("PAY-INT-6-4", 1000, "Success", "SUCCESS", "pg-int-6-4"));
		given(naverPayClient.cancel(any()))
			.willReturn(buildCancelResponse("Fail", "pg-int-6-4"));
		Mockito.doThrow(new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE))
			.when(paymentService)
			.completeApprove(eq("PAY-INT-6-4"), eq(PaymentProvider.NAVERPAY), eq("pg-int-6-4"), any(LocalDateTime.class));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(member.getId(), "PAY-INT-6-4", "pg-int-6-4"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_DUPLICATE));
		assertThat(getAttempt("PAY-INT-6-4", "pg-int-6-4", PaymentAttemptType.APPROVE).getStatus())
			.isEqualTo(PaymentAttemptStatus.FAILED);
		assertThat(getAttempt("PAY-INT-6-4", "pg-int-6-4", PaymentAttemptType.APPROVE).getFailCode())
			.isEqualTo(PaymentAttemptFailCode.DUPLICATE_PAYMENT);
		assertThat(getAttempt("PAY-INT-6-4", "pg-int-6-4", PaymentAttemptType.CANCEL).getStatus())
			.isEqualTo(PaymentAttemptStatus.FAILED);
	}

	@DisplayName("이미 취소 진행 중인 시도가 있으면 completeApprove 중복 예외에서도 cancel attempt를 REQUESTED로 유지한다")
	@Test
	void approve_whenCompleteApproveThrowsDuplicateAndCancelAlreadyOnGoing_keepCancelRequested() {
		// given
		Member member = createMember();
		createOrder(member, "PAY-INT-6-5", 1000);
		paymentAttemptRepository.saveAndFlush(
			PaymentAttempt.createCancelRequested("PAY-INT-6-5", "pg-int-6-5", 1000, PaymentProvider.NAVERPAY)
		);

		given(naverPayClient.approve("pg-int-6-5"))
			.willReturn(buildApprovalResponse("PAY-INT-6-5", 1000, "Success", "SUCCESS", "pg-int-6-5"));
		given(naverPayClient.cancel(any()))
			.willReturn(buildCancelResponse("AlreadyOnGoing", "pg-int-6-5"));
		Mockito.doThrow(new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE))
			.when(paymentService)
			.completeApprove(eq("PAY-INT-6-5"), eq(PaymentProvider.NAVERPAY), eq("pg-int-6-5"), any(LocalDateTime.class));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(member.getId(), "PAY-INT-6-5", "pg-int-6-5"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_DUPLICATE));
		assertThat(getAttempt("PAY-INT-6-5", "pg-int-6-5", PaymentAttemptType.CANCEL).getStatus())
			.isEqualTo(PaymentAttemptStatus.REQUESTED);
	}

	/**
	 * ===================================================
	 * 7. attempt 상태 분기
	 * ===================================================
	 */
	@DisplayName("approve attempt가 FAILED면 PG 호출 없이 즉시 예외를 던진다")
	@Test
	void approve_whenAttemptAlreadyFailed_throwImmediatelyWithoutPgCall() {
		// given
		Member member = createMember();
		createOrder(member, "PAY-INT-7-1", 1000);

		PaymentAttempt attempt = PaymentAttempt.createApproveRequested(
			"PAY-INT-7-1", "pg-int-7-1", 1000, PaymentProvider.NAVERPAY
		);
		attempt.approveFail(PaymentAttemptFailCode.TIME_EXPIRED, "expired", LocalDateTime.now());
		paymentAttemptRepository.saveAndFlush(attempt);

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(member.getId(), "PAY-INT-7-1", "pg-int-7-1"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_TIME_EXPIRED));
		then(naverPayClient).should(never()).approve(any());
	}

	/**
	 * ===================================================
	 * 8. 네트워크/PG 장애
	 * ===================================================
	 */
	@DisplayName("approve 호출에서 네트워크 오류가 발생하면 PG_NETWORK_ERROR로 실패 처리한다")
	@Test
	void approve_whenNetworkError_markApproveAttemptFailed() {
		// given
		Member member = createMember();
		createOrder(member, "PAY-INT-8-1", 1000);
		given(naverPayClient.approve("pg-int-8-1"))
			.willThrow(new NaverPayException(NaverPayErrorCode.NETWORK, "network error"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(member.getId(), "PAY-INT-8-1", "pg-int-8-1"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_PG_NETWORK_ERROR));
		assertThat(getAttempt("PAY-INT-8-1", "pg-int-8-1", PaymentAttemptType.APPROVE).getFailCode())
			.isEqualTo(PaymentAttemptFailCode.PG_NETWORK_ERROR);
	}

	@DisplayName("approve 호출에서 서버 오류가 발생하면 PG_SERVER_ERROR로 실패 처리한다")
	@Test
	void approve_whenServerError_markApproveAttemptFailed() {
		// given
		Member member = createMember();
		createOrder(member, "PAY-INT-8-2", 1000);
		given(naverPayClient.approve("pg-int-8-2"))
			.willThrow(new NaverPayException(NaverPayErrorCode.SERVER_ERROR, "server error"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(member.getId(), "PAY-INT-8-2", "pg-int-8-2"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_PG_SERVER_ERROR));
		assertThat(getAttempt("PAY-INT-8-2", "pg-int-8-2", PaymentAttemptType.APPROVE).getFailCode())
			.isEqualTo(PaymentAttemptFailCode.PG_SERVER_ERROR);
	}

	@DisplayName("approve 성공 응답인데 body가 비어있으면 INVALID_RESPONSE 예외가 발생한다")
	@Test
	void approve_whenApproveResponseBodyIsNull_throwInvalidResponseException() {
		// given
		Member member = createMember();
		createOrder(member, "PAY-INT-8-3", 1000);
		NaverPayResponse<NaverPayApproveBody> response = new NaverPayResponse<>();
		ReflectionTestUtils.setField(response, "code", "Success");
		given(naverPayClient.approve("pg-int-8-3"))
			.willReturn(response);

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(member.getId(), "PAY-INT-8-3", "pg-int-8-3"))
			.isInstanceOf(NaverPayException.class)
			.satisfies(exception -> {
				NaverPayException naverPayException = (NaverPayException)exception;
				assertThat(naverPayException.getErrorCode()).isEqualTo(NaverPayErrorCode.INVALID_RESPONSE);
			});
	}

	@DisplayName("approve 응답이 MaintenanceOngoing이면 PG_MAINTENANCE 예외를 던진다")
	@Test
	void approve_whenMaintenanceCode_throwPgMaintenanceException() {
		// given
		Member member = createMember();
		createOrder(member, "PAY-INT-8-4", 1000);
		given(naverPayClient.approve("pg-int-8-4"))
			.willReturn(buildApprovalResponse("PAY-INT-8-4", 1000, "MaintenanceOngoing", "SUCCESS", "pg-int-8-4"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(member.getId(), "PAY-INT-8-4", "pg-int-8-4"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_PG_MAINTENANCE));
		assertThat(getAttempt("PAY-INT-8-4", "pg-int-8-4", PaymentAttemptType.APPROVE).getFailCode())
			.isEqualTo(PaymentAttemptFailCode.PG_MAINTENANCE);
	}

	/**
	 * ===================================================
	 * 9. DB/서버 장애
	 * ===================================================
	 */
	@DisplayName("completeApprove 중 주문 상태 예외가 발생하면 승인 취소를 요청한다")
	@Test
	void approve_whenOrderAlreadyPaid_cancelApprovedPaymentAndThrowException() {
		// given
		Member member = createMember();
		Order order = createOrder(member, "PAY-INT-9-1", 1000);
		order.completePayment();
		orderRepository.saveAndFlush(order);

		given(naverPayClient.approve("pg-int-9-1"))
			.willReturn(buildApprovalResponse("PAY-INT-9-1", 1000, "Success", "SUCCESS", "pg-int-9-1"));
		given(naverPayClient.cancel(any()))
			.willReturn(buildCancelResponse("Success", "pg-int-9-1"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(member.getId(), "PAY-INT-9-1", "pg-int-9-1"))
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> assertThat(((OrderException)exception).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_PAID_NOT_ALLOWED));
		assertThat(getAttempt("PAY-INT-9-1", "pg-int-9-1", PaymentAttemptType.CANCEL).getStatus())
			.isEqualTo(PaymentAttemptStatus.SUCCEEDED);
	}

	@DisplayName("completeApprove에서 예상치 못한 예외가 발생하면 승인 취소를 요청하고 예외를 그대로 던진다")
	@Test
	void approve_whenCompleteApproveThrowsUnexpectedException_cancelApprovedPaymentAndThrowException() {
		// given
		Member member = createMember();
		createOrder(member, "PAY-INT-9-2", 1000);
		given(naverPayClient.approve("pg-int-9-2"))
			.willReturn(buildApprovalResponse("PAY-INT-9-2", 1000, "Success", "SUCCESS", "pg-int-9-2"));
		given(naverPayClient.cancel(any()))
			.willReturn(buildCancelResponse("Success", "pg-int-9-2"));
		Mockito.doThrow(new RuntimeException("db write failed"))
			.when(paymentService)
			.completeApprove(eq("PAY-INT-9-2"), eq(PaymentProvider.NAVERPAY), eq("pg-int-9-2"), any(LocalDateTime.class));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(member.getId(), "PAY-INT-9-2", "pg-int-9-2"))
			.isInstanceOf(RuntimeException.class)
			.hasMessage("db write failed");
		assertThat(getAttempt("PAY-INT-9-2", "pg-int-9-2", PaymentAttemptType.APPROVE).getStatus())
			.isEqualTo(PaymentAttemptStatus.FAILED);
		assertThat(getAttempt("PAY-INT-9-2", "pg-int-9-2", PaymentAttemptType.APPROVE).getFailCode())
			.isEqualTo(PaymentAttemptFailCode.APPROVE_PROCESS_FAILED);
		assertThat(getAttempt("PAY-INT-9-2", "pg-int-9-2", PaymentAttemptType.CANCEL).getStatus())
			.isEqualTo(PaymentAttemptStatus.SUCCEEDED);
	}

	@DisplayName("취소 API는 성공했지만 취소 성공 반영이 실패해도 원래 승인 실패 예외를 유지한다")
	@Test
	void approve_whenCancelSuccessButSucceedCancelAttemptFails_keepOriginalException() {
		// given
		Member member = createMember();
		createOrder(member, "PAY-INT-9-2", 1000);
		given(naverPayClient.approve("pg-int-9-2"))
			.willReturn(buildApprovalResponse("PAY-INT-9-2", 2000, "Success", "SUCCESS", "pg-int-9-2"));
		given(naverPayClient.cancel(any()))
			.willReturn(buildCancelResponse("Success", "pg-int-9-2"));
		Mockito.doThrow(new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_NOT_FOUND))
			.when(paymentAttemptService)
			.succeedCancelAttempt(eq("PAY-INT-9-2"), eq(PaymentProvider.NAVERPAY), eq("pg-int-9-2"), any(LocalDateTime.class));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(member.getId(), "PAY-INT-9-2", "pg-int-9-2"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH));
		then(naverPayClient).should().cancel(any());
	}

	@DisplayName("취소 API는 실패 응답을 줬지만 취소 실패 반영이 실패해도 원래 승인 실패 예외를 유지한다")
	@Test
	void approve_whenCancelFailAndFailCancelAttemptFails_keepOriginalException() {
		// given
		Member member = createMember();
		createOrder(member, "PAY-INT-9-3", 1000);
		given(naverPayClient.approve("pg-int-9-3"))
			.willReturn(buildApprovalResponse("PAY-INT-9-3", 2000, "Success", "SUCCESS", "pg-int-9-3"));
		given(naverPayClient.cancel(any()))
			.willReturn(buildCancelResponse("Fail", "pg-int-9-3"));
		Mockito.doThrow(new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_NOT_FOUND))
			.when(paymentAttemptService)
			.failCancelAttempt(
				eq("PAY-INT-9-3"),
				eq(PaymentProvider.NAVERPAY),
				eq("pg-int-9-3"),
				eq(PaymentAttemptFailCode.PG_REQUEST_REJECTED),
				eq("기타 실패"),
				any(LocalDateTime.class)
			);

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(member.getId(), "PAY-INT-9-3", "pg-int-9-3"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH));
		then(naverPayClient).should().cancel(any());
	}

	/**
	 * ===================================================
	 * 10. 보안/악의적 시도
	 * ===================================================
	 */
	@DisplayName("다른 회원이 merchantPayKey로 승인 요청하면 ORDER_NOT_FOUND가 발생한다")
	@Test
	void approve_whenMemberDoesNotOwnMerchantPayKey_throwOrderNotFound() {
		// given
		Member owner = createMember();
		Member attacker = createMember();
		createOrder(owner, "PAY-INT-10-1", 1000);

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(attacker.getId(), "PAY-INT-10-1", "pg-int-10-1"))
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> assertThat(((OrderException)exception).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_NOT_FOUND));
	}

	@DisplayName("다른 사용자의 paymentId로 승인 응답을 받아 merchantPayKey가 다르면 실패 처리하고 취소하지 않는다")
	@Test
	void approve_whenForeignPaymentIdReturnsDifferentMerchantPayKey_failAttemptWithoutCancel() {
		// given
		Member member = createMember();
		createOrder(member, "PAY-INT-10-2", 1000);
		given(naverPayClient.approve("pg-foreign-10-2"))
			.willReturn(buildApprovalResponse("OTHER-PAY", 1000, "Success", "SUCCESS", "pg-foreign-10-2"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(member.getId(), "PAY-INT-10-2", "pg-foreign-10-2"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_MERCHANT_KEY_MISMATCH));
		assertThat(paymentRepository.findByMerchantPayKey("PAY-INT-10-2")).isEmpty();
		assertThat(getAttempt("PAY-INT-10-2", "pg-foreign-10-2", PaymentAttemptType.APPROVE).getStatus())
			.isEqualTo(PaymentAttemptStatus.FAILED);
		assertCancelAttemptEmpty("PAY-INT-10-2", "pg-foreign-10-2");
		then(naverPayClient).should(never()).cancel(any());
	}

	@DisplayName("다른 사용자의 paymentId로 AlreadyComplete 응답을 받았고 history merchantPayKey가 다르면 실패 처리하고 취소하지 않는다")
	@Test
	void approve_whenForeignPaymentIdHistoryReturnsDifferentMerchantPayKey_failAttemptWithoutCancel() {
		// given
		Member member = createMember();
		createOrder(member, "PAY-INT-10-3", 1000);
		given(naverPayClient.approve("pg-foreign-10-3"))
			.willReturn(buildApprovalResponse("PAY-INT-10-3", 1000, "AlreadyComplete", "SUCCESS", "pg-foreign-10-3"));
		given(naverPayClient.getAllHistory("pg-foreign-10-3"))
			.willReturn(buildHistoryResponse("OTHER-PAY", 1000, "SUCCESS", "01", "pg-foreign-10-3"));

		// when & then
		assertThatThrownBy(() -> naverPayService.approve(member.getId(), "PAY-INT-10-3", "pg-foreign-10-3"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND));
		assertThat(paymentRepository.findByMerchantPayKey("PAY-INT-10-3")).isEmpty();
		assertThat(getAttempt("PAY-INT-10-3", "pg-foreign-10-3", PaymentAttemptType.APPROVE).getStatus())
			.isEqualTo(PaymentAttemptStatus.FAILED);
		assertCancelAttemptEmpty("PAY-INT-10-3", "pg-foreign-10-3");
		then(naverPayClient).should(never()).cancel(any());
	}

	/**
	 * ===================================================
	 * 11. 멱등성/동시성
	 * -> NaverPayServiceConcurrencyTest
	 * ===================================================
	 */

	/**
	 * ===================================================
	 * 12. 운영 복구/배치
	 * ===================================================
	 */
	@DisplayName("approve attempt가 SUCCEEDED인데 payment가 없으면 history 기반으로 복구 반영한다")
	@Test
	void approve_whenAttemptSucceededAndPaymentMissing_recoverByHistory() {
		// given
		Member member = createMember();
		createOrder(member, "PAY-INT-12-1", 1000);
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested(
			"PAY-INT-12-1", "pg-int-12-1", 1000, PaymentProvider.NAVERPAY
		);
		attempt.approveSucceed(LocalDateTime.now());
		paymentAttemptRepository.saveAndFlush(attempt);
		given(naverPayClient.getAllHistory("pg-int-12-1"))
			.willReturn(buildHistoryResponse("PAY-INT-12-1", 1000, "SUCCESS", "01", "pg-int-12-1"));

		// when
		NaverPayApproveResult result = naverPayService.approve(member.getId(), "PAY-INT-12-1", "pg-int-12-1");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.SUCCESS);
		assertThat(paymentRepository.findByMerchantPayKey("PAY-INT-12-1")).isPresent();
		then(naverPayClient).should(never()).approve(any());
		then(naverPayClient).should().getAllHistory("pg-int-12-1");
	}

	private Member createMember() {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		return memberRepository.save(
			Member.builder()
				.email("naverpay-int-" + suffix + "@example.com")
				.password("password123")
				.username("u" + suffix)
				.build()
		);
	}

	private Order createOrder(Member member, String merchantPayKey, int totalPrice) {
		Product product = productRepository.save(
			Product.builder()
				.name("product-" + merchantPayKey)
				.price(totalPrice)
				.status(ProductStatus.ON_SALE)
				.build()
		);

		Order order = Order.create(member);
		order.addOrderItem(product, 1);
		order.assignMerchantPayKey(merchantPayKey);
		return orderRepository.saveAndFlush(order);
	}

	private PaymentAttempt getAttempt(String merchantPayKey, String paymentId, PaymentAttemptType type) {
		return paymentAttemptRepository.findByMerchantPayKeyAndProviderAndPaymentIdAndType(
			merchantPayKey,
			PaymentProvider.NAVERPAY,
			paymentId,
			type
		).orElseThrow();
	}

	private void assertCancelAttemptEmpty(String merchantPayKey, String paymentId) {
		assertThat(paymentAttemptRepository.findByMerchantPayKeyAndProviderAndPaymentIdAndType(
			merchantPayKey, PaymentProvider.NAVERPAY, paymentId, PaymentAttemptType.CANCEL
		)).isEmpty();
	}

	private NaverPayResponse<NaverPayApproveBody> buildApprovalResponse(
		String merchantPayKey,
		int amount,
		String code,
		String state,
		String paymentId
	) {
		NaverPayResponse<NaverPayApproveBody> response = new NaverPayResponse<>();
		NaverPayApproveBody body = new NaverPayApproveBody();
		NaverPayApproveBody.Detail detail = new NaverPayApproveBody.Detail();

		ReflectionTestUtils.setField(response, "code", code);
		ReflectionTestUtils.setField(detail, "paymentId", paymentId);
		ReflectionTestUtils.setField(detail, "merchantPayKey", merchantPayKey);
		ReflectionTestUtils.setField(detail, "admissionState", state);
		ReflectionTestUtils.setField(detail, "totalPayAmount", amount);
		ReflectionTestUtils.setField(body, "detail", detail);
		ReflectionTestUtils.setField(response, "body", body);
		return response;
	}

	private NaverPayResponse<NaverPayCancelBody> buildCancelResponse(String code, String paymentId) {
		NaverPayResponse<NaverPayCancelBody> response = new NaverPayResponse<>();
		NaverPayCancelBody body = new NaverPayCancelBody();

		ReflectionTestUtils.setField(response, "code", code);
		ReflectionTestUtils.setField(body, "paymentId", paymentId);
		ReflectionTestUtils.setField(response, "body", body);
		return response;
	}

	private NaverPayResponse<NaverPayHistoryBody> buildHistoryResponse(
		String merchantPayKey,
		int totalPayAmount,
		String admissionState,
		String admissionTypeCode,
		String paymentId
	) {
		NaverPayResponse<NaverPayHistoryBody> response = new NaverPayResponse<>();
		NaverPayHistoryBody body = new NaverPayHistoryBody();
		NaverPayHistoryBody.History history = new NaverPayHistoryBody.History();
		ArrayList<NaverPayHistoryBody.History> list = new ArrayList<>();

		ReflectionTestUtils.setField(response, "code", "Success");
		ReflectionTestUtils.setField(history, "paymentId", paymentId);
		ReflectionTestUtils.setField(history, "merchantPayKey", merchantPayKey);
		ReflectionTestUtils.setField(history, "admissionState", admissionState);
		ReflectionTestUtils.setField(history, "admissionTypeCode", admissionTypeCode);
		ReflectionTestUtils.setField(history, "totalPayAmount", totalPayAmount);
		list.add(history);
		ReflectionTestUtils.setField(body, "list", list);
		ReflectionTestUtils.setField(response, "body", body);
		return response;
	}

	private NaverPayResponse<NaverPayHistoryBody> buildHistoryResponseWithCode(
		String merchantPayKey,
		int totalPayAmount,
		String admissionState,
		String admissionTypeCode,
		String paymentId,
		String code
	) {
		NaverPayResponse<NaverPayHistoryBody> response = buildHistoryResponse(
			merchantPayKey,
			totalPayAmount,
			admissionState,
			admissionTypeCode,
			paymentId
		);
		ReflectionTestUtils.setField(response, "code", code);
		return response;
	}

	private NaverPayResponse<NaverPayHistoryBody> buildEmptyHistoryResponse() {
		NaverPayResponse<NaverPayHistoryBody> response = new NaverPayResponse<>();
		NaverPayHistoryBody body = new NaverPayHistoryBody();
		ReflectionTestUtils.setField(response, "code", "Success");
		ReflectionTestUtils.setField(body, "list", new ArrayList<>());
		ReflectionTestUtils.setField(response, "body", body);
		return response;
	}
}
