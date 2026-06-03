package com.commerce.payment.naverpay.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.commerce.member.domain.Member;
import com.commerce.order.domain.Order;
import com.commerce.order.domain.OrderStatus;
import com.commerce.order.exception.OrderErrorCode;
import com.commerce.order.exception.OrderException;
import com.commerce.payment.domain.Payment;
import com.commerce.payment.domain.PaymentAttempt;
import com.commerce.payment.domain.PaymentAttemptFailCode;
import com.commerce.payment.domain.PaymentAttemptStatus;
import com.commerce.payment.domain.PaymentAttemptType;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.exception.PaymentErrorCode;
import com.commerce.payment.exception.PaymentException;
import com.commerce.payment.naverpay.application.NaverPayApprovalService;
import com.commerce.payment.naverpay.application.result.NaverPayApproveResponse;
import com.commerce.payment.naverpay.application.result.NaverPayApproveStatus;
import com.commerce.payment.naverpay.application.port.NaverPayGateway;
import com.commerce.payment.naverpay.application.port.result.NaverPayApproveResult;
import com.commerce.payment.naverpay.application.port.result.NaverPayCancelResult;
import com.commerce.payment.naverpay.application.port.result.NaverPayHistoryResult;
import com.commerce.payment.application.PaymentApprovalService;
import com.commerce.payment.application.PaymentCancellationAttemptService;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.member.infrastructure.persistence.support.MemberPersistenceTestSupport;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;

@SpringBootTest
@ActiveProfiles("test")
@Import({PersistenceCleanupTestSupport.class, PaymentPersistenceTestSupport.class, MemberPersistenceTestSupport.class, ProductPersistenceTestSupport.class, OrderPersistenceTestSupport.class})
class NaverPayServiceIntegrationTest {

	@Autowired
	private NaverPayApprovalService naverPayApprovalService;

	@Autowired
	private MemberPersistenceTestSupport memberPersistence;

	@Autowired
	private ProductPersistenceTestSupport productPersistence;

	@Autowired
	private OrderPersistenceTestSupport orderPersistence;

	@Autowired
	private PaymentPersistenceTestSupport paymentPersistence;

	@MockitoBean
	private NaverPayGateway naverPayGateway;

	@MockitoSpyBean
	private PaymentApprovalService paymentApprovalService;

	// succeed / fail 강제 예외 주입 시나리오용 spy.
	// find-first 리팩토링 이후 H2 우회용 getOrCreate*Attempt doReturn 스텁은 모두 제거됐다.
	@MockitoSpyBean
	private PaymentCancellationAttemptService paymentCancellationAttemptService;

	@Autowired
	private PersistenceCleanupTestSupport persistenceCleanup;

	@AfterEach
	void tearDown() {
		Mockito.reset(naverPayGateway, paymentApprovalService, paymentCancellationAttemptService);
		persistenceCleanup.deleteAllInBatch(
			paymentPersistence, memberPersistence, productPersistence, orderPersistence
		);
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
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-1", 1000);
		given(naverPayGateway.approve("pg-int-1"))
			.willReturn(NaverPayApproveResult.success("PAY-INT-1", 1000));

		// when
		NaverPayApproveResponse result = naverPayApprovalService.approve(member.getId(), "PAY-INT-1", "pg-int-1");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.SUCCESS);
		assertThat(result.getPgPaymentId()).isEqualTo("pg-int-1");
		assertThat(paymentPersistence.findPaymentByMerchantPayKey("PAY-INT-1")).isPresent();
		assertThat(orderPersistence.getOrderStatusByMerchantPayKey("PAY-INT-1")).isEqualTo(OrderStatus.PAID);
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
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-2-1", 1000);
		given(naverPayGateway.approve("pg-int-2-1"))
			.willReturn(NaverPayApproveResult.processing());

		// when
		NaverPayApproveResponse result = naverPayApprovalService.approve(member.getId(), "PAY-INT-2-1", "pg-int-2-1");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.PROCESSING);
		assertThat(paymentPersistence.findPaymentByMerchantPayKey("PAY-INT-2-1")).isEmpty();
		assertThat(getAttempt("PAY-INT-2-1", "pg-int-2-1", PaymentAttemptType.APPROVE).getStatus())
			.isEqualTo(PaymentAttemptStatus.REQUESTED);
	}

	@DisplayName("AlreadyComplete 응답이면 history를 조회해서 결제를 완료 처리한다")
	@Test
	void approve_whenAlreadyComplete_completeByHistory() {
		// given
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-2-2", 1000);
		given(naverPayGateway.approve("pg-int-2-2"))
			.willReturn(NaverPayApproveResult.alreadyComplete());
		given(naverPayGateway.getApprovalHistory("pg-int-2-2"))
			.willReturn(NaverPayHistoryResult.approved("PAY-INT-2-2", 1000));

		// when
		NaverPayApproveResponse result = naverPayApprovalService.approve(member.getId(), "PAY-INT-2-2", "pg-int-2-2");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.SUCCESS);
		assertThat(paymentPersistence.findPaymentByMerchantPayKey("PAY-INT-2-2")).isPresent();
		then(naverPayGateway).should().getApprovalHistory("pg-int-2-2");
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
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-3-1", 1000);
		given(naverPayGateway.approve("pg-int-3-1"))
			.willReturn(NaverPayApproveResult.alreadyComplete());
		given(naverPayGateway.getApprovalHistory("pg-int-3-1"))
			.willReturn(NaverPayHistoryResult.failed(PaymentErrorCode.PAYMENT_INVALID_MERCHANT));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-3-1", "pg-int-3-1"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_INVALID_MERCHANT));
	}

	@DisplayName("history 최신 이력이 승인 완료가 아니면 PAYMENT_NOT_FOUND를 던진다")
	@Test
	void approve_whenAlreadyCompleteAndHistoryNotCompleted_throwNotFound() {
		// given
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-3-2", 1000);
		given(naverPayGateway.approve("pg-int-3-2"))
			.willReturn(NaverPayApproveResult.alreadyComplete());
		given(naverPayGateway.getApprovalHistory("pg-int-3-2"))
			.willReturn(NaverPayHistoryResult.failed(PaymentErrorCode.PAYMENT_NOT_FOUND));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-3-2", "pg-int-3-2"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND));
	}

	@DisplayName("AlreadyComplete 응답 이후 history가 취소 완료 상태면 approve attempt를 ALREADY_CANCELED로 실패 처리하고 PAYMENT_ALREADY_CANCELED를 던진다")
	@Test
	void approve_whenAlreadyCompleteAndHistoryCanceled_throwAlreadyCanceled() {
		// given
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-3-2-C", 1000);
		given(naverPayGateway.approve("pg-int-3-2-c"))
			.willReturn(NaverPayApproveResult.alreadyComplete());
		given(naverPayGateway.getApprovalHistory("pg-int-3-2-c"))
			.willReturn(NaverPayHistoryResult.canceled());

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-3-2-C", "pg-int-3-2-c"))
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
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-3-3", 1000);
		given(naverPayGateway.approve("pg-int-3-3"))
			.willReturn(NaverPayApproveResult.alreadyComplete());
		given(naverPayGateway.getApprovalHistory("pg-int-3-3"))
			.willReturn(NaverPayHistoryResult.failed(PaymentErrorCode.PAYMENT_NOT_FOUND));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-3-3", "pg-int-3-3"))
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
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-4-1", 1000);
		given(naverPayGateway.approve("pg-int-4-1"))
			.willReturn(NaverPayApproveResult.success("OTHER-PAY", 1000));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-4-1", "pg-int-4-1"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_MERCHANT_KEY_MISMATCH));
		assertThat(getAttempt("PAY-INT-4-1", "pg-int-4-1", PaymentAttemptType.APPROVE).getStatus())
			.isEqualTo(PaymentAttemptStatus.FAILED);
		assertCancelAttemptEmpty("PAY-INT-4-1", "pg-int-4-1");
		then(naverPayGateway).should(never()).cancel(any(), anyInt(), any());
	}

	@DisplayName("history 경로 merchantPayKey가 다르면 approve attempt를 FAILED로 저장하고 취소는 요청하지 않는다")
	@Test
	void approve_whenHistoryMerchantPayKeyMismatch_failAttemptWithoutCancel() {
		// given
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-4-2", 1000);
		given(naverPayGateway.approve("pg-int-4-2"))
			.willReturn(NaverPayApproveResult.alreadyComplete());
		given(naverPayGateway.getApprovalHistory("pg-int-4-2"))
			.willReturn(NaverPayHistoryResult.approved("OTHER-PAY", 1000));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-4-2", "pg-int-4-2"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND));
		assertThat(getAttempt("PAY-INT-4-2", "pg-int-4-2", PaymentAttemptType.APPROVE).getStatus())
			.isEqualTo(PaymentAttemptStatus.FAILED);
		assertCancelAttemptEmpty("PAY-INT-4-2", "pg-int-4-2");
		then(naverPayGateway).should(never()).cancel(any(), anyInt(), any());
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
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-5-1", 1000);
		given(naverPayGateway.approve("pg-int-5-1"))
			.willReturn(NaverPayApproveResult.success("PAY-INT-5-1", 2000));
		given(naverPayGateway.cancel(any(), anyInt(), any()))
			.willReturn(NaverPayCancelResult.success());

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-5-1", "pg-int-5-1"))
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
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-5-2", 1000);
		given(naverPayGateway.approve("pg-int-5-2"))
			.willReturn(NaverPayApproveResult.success("PAY-INT-5-2", 2000));
		given(naverPayGateway.cancel(any(), anyInt(), any()))
			.willReturn(NaverPayCancelResult.alreadyCanceled());

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-5-2", "pg-int-5-2"))
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
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-5-3", 1000);
		given(naverPayGateway.approve("pg-int-5-3"))
			.willReturn(NaverPayApproveResult.success("PAY-INT-5-3", 2000));
		given(naverPayGateway.cancel(any(), anyInt(), any()))
			.willReturn(NaverPayCancelResult.processing());

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-5-3", "pg-int-5-3"))
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
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-5-4", 1000);
		given(naverPayGateway.approve("pg-int-5-4"))
			.willReturn(NaverPayApproveResult.success("PAY-INT-5-4", 2000));
		given(naverPayGateway.cancel(any(), anyInt(), any()))
			.willReturn(NaverPayCancelResult.failed(PaymentAttemptFailCode.PG_REQUEST_REJECTED, "기타 실패"));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-5-4", "pg-int-5-4"))
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
		Member member = memberPersistence.save(createMember());
		Order order = persistOrder(member, "PAY-INT-6-1", 1000);
		paymentPersistence.save(
			Payment.createCompleted(order.getId(), order.getTotalPrice(), PaymentProvider.NAVERPAY, "PAY-INT-6-1", "pg-existing", LocalDateTime.now())
		);

		// when
		NaverPayApproveResponse result = naverPayApprovalService.approve(member.getId(), "PAY-INT-6-1", "pg-int-6-1");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.SUCCESS);
		assertThat(result.getPgPaymentId()).isEqualTo("pg-existing");
		then(naverPayGateway).should(never()).approve(any());
	}

	@DisplayName("completeApprovedPayment에서 PAYMENT_DUPLICATE가 발생하면 현재 승인건 취소를 요청한다")
	@Test
	void approve_whenCompleteApproveThrowsDuplicate_cancelApprovedPayment() {
		// given
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-6-2", 1000);
		given(naverPayGateway.approve("pg-int-6-2"))
			.willReturn(NaverPayApproveResult.success("PAY-INT-6-2", 1000));
		given(naverPayGateway.cancel(any(), anyInt(), any()))
			.willReturn(NaverPayCancelResult.success());
		Mockito.doThrow(new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE))
			.when(paymentApprovalService)
			.completeApprovedPayment(eq("PAY-INT-6-2"), eq(PaymentProvider.NAVERPAY), eq("pg-int-6-2"), any(LocalDateTime.class));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-6-2", "pg-int-6-2"))
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

	@DisplayName("이미 취소 완료된 시도가 있으면 completeApprovedPayment 중복 예외에서도 취소를 다시 요청하지 않는다")
	@Test
	void approve_whenCompleteApproveThrowsDuplicateAndCancelAlreadySucceeded_skipCancelRequest() {
		// given
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-6-3", 1000);
		PaymentAttempt cancelAttempt = PaymentAttempt.createCancelRequested(
			"PAY-INT-6-3", "pg-int-6-3", 1000, PaymentProvider.NAVERPAY
		);
		cancelAttempt.succeed(LocalDateTime.now());
		paymentPersistence.save(cancelAttempt);

		given(naverPayGateway.approve("pg-int-6-3"))
			.willReturn(NaverPayApproveResult.success("PAY-INT-6-3", 1000));
		Mockito.doThrow(new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE))
			.when(paymentApprovalService)
			.completeApprovedPayment(eq("PAY-INT-6-3"), eq(PaymentProvider.NAVERPAY), eq("pg-int-6-3"), any(LocalDateTime.class));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-6-3", "pg-int-6-3"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_DUPLICATE));
		assertThat(getAttempt("PAY-INT-6-3", "pg-int-6-3", PaymentAttemptType.CANCEL).getStatus())
			.isEqualTo(PaymentAttemptStatus.SUCCEEDED);
		then(naverPayGateway).should(never()).cancel(any(), anyInt(), any());
	}

	@DisplayName("completeApprovedPayment에서 PAYMENT_DUPLICATE가 발생하고 취소가 실패하면 cancel attempt를 FAILED로 저장한다")
	@Test
	void approve_whenCompleteApproveThrowsDuplicateAndCancelFail_markCancelFailed() {
		// given
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-6-4", 1000);
		given(naverPayGateway.approve("pg-int-6-4"))
			.willReturn(NaverPayApproveResult.success("PAY-INT-6-4", 1000));
		given(naverPayGateway.cancel(any(), anyInt(), any()))
			.willReturn(NaverPayCancelResult.failed(PaymentAttemptFailCode.PG_REQUEST_REJECTED, "기타 실패"));
		Mockito.doThrow(new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE))
			.when(paymentApprovalService)
			.completeApprovedPayment(eq("PAY-INT-6-4"), eq(PaymentProvider.NAVERPAY), eq("pg-int-6-4"), any(LocalDateTime.class));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-6-4", "pg-int-6-4"))
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

	@DisplayName("이미 취소 진행 중인 시도가 있으면 completeApprovedPayment 중복 예외에서도 cancel attempt를 REQUESTED로 유지한다")
	@Test
	void approve_whenCompleteApproveThrowsDuplicateAndCancelAlreadyOnGoing_keepCancelRequested() {
		// given
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-6-5", 1000);
		paymentPersistence.save(
			PaymentAttempt.createCancelRequested("PAY-INT-6-5", "pg-int-6-5", 1000, PaymentProvider.NAVERPAY)
		);

		given(naverPayGateway.approve("pg-int-6-5"))
			.willReturn(NaverPayApproveResult.success("PAY-INT-6-5", 1000));
		given(naverPayGateway.cancel(any(), anyInt(), any()))
			.willReturn(NaverPayCancelResult.processing());
		Mockito.doThrow(new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE))
			.when(paymentApprovalService)
			.completeApprovedPayment(eq("PAY-INT-6-5"), eq(PaymentProvider.NAVERPAY), eq("pg-int-6-5"), any(LocalDateTime.class));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-6-5", "pg-int-6-5"))
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
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-7-1", 1000);

		PaymentAttempt attempt = PaymentAttempt.createApproveRequested(
			"PAY-INT-7-1", "pg-int-7-1", 1000, PaymentProvider.NAVERPAY
		);
		attempt.fail(PaymentAttemptFailCode.TIME_EXPIRED, "expired", LocalDateTime.now());
		paymentPersistence.save(attempt);

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-7-1", "pg-int-7-1"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_TIME_EXPIRED));
		then(naverPayGateway).should(never()).approve(any());
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
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-8-1", 1000);
		given(naverPayGateway.approve("pg-int-8-1"))
			.willReturn(NaverPayApproveResult.failed(
				PaymentAttemptFailCode.PG_NETWORK_ERROR, PaymentErrorCode.PAYMENT_PG_NETWORK_ERROR, "network error"));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-8-1", "pg-int-8-1"))
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
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-8-2", 1000);
		given(naverPayGateway.approve("pg-int-8-2"))
			.willReturn(NaverPayApproveResult.failed(
				PaymentAttemptFailCode.PG_SERVER_ERROR, PaymentErrorCode.PAYMENT_PG_SERVER_ERROR, "server error"));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-8-2", "pg-int-8-2"))
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
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-8-3", 1000);
		given(naverPayGateway.approve("pg-int-8-3"))
			.willReturn(NaverPayApproveResult.failed(
				PaymentAttemptFailCode.PG_INVALID_RESPONSE,
				PaymentErrorCode.PAYMENT_PG_INVALID_RESPONSE,
				"네이버페이 응답 처리에 실패했습니다"));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-8-3", "pg-int-8-3"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> {
				PaymentException paymentException = (PaymentException)exception;
				assertThat(paymentException.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_PG_INVALID_RESPONSE);
			});
	}

	@DisplayName("approve 응답이 MaintenanceOngoing이면 PG_MAINTENANCE 예외를 던진다")
	@Test
	void approve_whenMaintenanceCode_throwPgMaintenanceException() {
		// given
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-8-4", 1000);
		given(naverPayGateway.approve("pg-int-8-4"))
			.willReturn(NaverPayApproveResult.failed(
				PaymentAttemptFailCode.PG_MAINTENANCE, PaymentErrorCode.PAYMENT_PG_MAINTENANCE, "서비스 점검중"));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-8-4", "pg-int-8-4"))
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
	@DisplayName("completeApprovedPayment 중 주문 상태 예외가 발생하면 승인 취소를 요청한다")
	@Test
	void approve_whenOrderAlreadyPaid_cancelApprovedPaymentAndThrowException() {
		// given
		Member member = memberPersistence.save(createMember());
		Order order = persistOrder(member, "PAY-INT-9-1", 1000);
		order.completePayment();
		orderPersistence.save(order);

		given(naverPayGateway.approve("pg-int-9-1"))
			.willReturn(NaverPayApproveResult.success("PAY-INT-9-1", 1000));
		given(naverPayGateway.cancel(any(), anyInt(), any()))
			.willReturn(NaverPayCancelResult.success());

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-9-1", "pg-int-9-1"))
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> assertThat(((OrderException)exception).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_PAID_NOT_ALLOWED));
		assertThat(getAttempt("PAY-INT-9-1", "pg-int-9-1", PaymentAttemptType.CANCEL).getStatus())
			.isEqualTo(PaymentAttemptStatus.SUCCEEDED);
	}

	@DisplayName("completeApprovedPayment에서 예상치 못한 예외가 발생하면 승인 취소를 요청하고 예외를 그대로 던진다")
	@Test
	void approve_whenCompleteApproveThrowsUnexpectedException_cancelApprovedPaymentAndThrowException() {
		// given
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-9-2", 1000);
		given(naverPayGateway.approve("pg-int-9-2"))
			.willReturn(NaverPayApproveResult.success("PAY-INT-9-2", 1000));
		given(naverPayGateway.cancel(any(), anyInt(), any()))
			.willReturn(NaverPayCancelResult.success());
		Mockito.doThrow(new RuntimeException("db write failed"))
			.when(paymentApprovalService)
			.completeApprovedPayment(eq("PAY-INT-9-2"), eq(PaymentProvider.NAVERPAY), eq("pg-int-9-2"), any(LocalDateTime.class));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-9-2", "pg-int-9-2"))
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
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-9-2", 1000);
		given(naverPayGateway.approve("pg-int-9-2"))
			.willReturn(NaverPayApproveResult.success("PAY-INT-9-2", 2000));
		given(naverPayGateway.cancel(any(), anyInt(), any()))
			.willReturn(NaverPayCancelResult.success());
		Mockito.doThrow(new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_NOT_FOUND))
			.when(paymentCancellationAttemptService)
			.succeed(eq("PAY-INT-9-2"), eq(PaymentProvider.NAVERPAY), eq("pg-int-9-2"), any(LocalDateTime.class));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-9-2", "pg-int-9-2"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH));
		then(naverPayGateway).should().cancel(any(), anyInt(), any());
	}

	@DisplayName("취소 API는 실패 응답을 줬지만 취소 실패 반영이 실패해도 원래 승인 실패 예외를 유지한다")
	@Test
	void approve_whenCancelFailAndFailCancelAttemptFails_keepOriginalException() {
		// given
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-9-3", 1000);
		given(naverPayGateway.approve("pg-int-9-3"))
			.willReturn(NaverPayApproveResult.success("PAY-INT-9-3", 2000));
		given(naverPayGateway.cancel(any(), anyInt(), any()))
			.willReturn(NaverPayCancelResult.failed(PaymentAttemptFailCode.PG_REQUEST_REJECTED, "기타 실패"));
		Mockito.doThrow(new PaymentException(PaymentErrorCode.PAYMENT_ATTEMPT_NOT_FOUND))
			.when(paymentCancellationAttemptService)
			.fail(
				eq("PAY-INT-9-3"),
				eq(PaymentProvider.NAVERPAY),
				eq("pg-int-9-3"),
				eq(PaymentAttemptFailCode.PG_REQUEST_REJECTED),
				eq("기타 실패"),
				any(LocalDateTime.class)
			);

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-9-3", "pg-int-9-3"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH));
		then(naverPayGateway).should().cancel(any(), anyInt(), any());
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
		Member owner = memberPersistence.save(createMember());
		Member attacker = memberPersistence.save(createMember());
		persistOrder(owner, "PAY-INT-10-1", 1000);

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(attacker.getId(), "PAY-INT-10-1", "pg-int-10-1"))
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> assertThat(((OrderException)exception).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_NOT_FOUND));
	}

	@DisplayName("다른 사용자의 pgPaymentId로 승인 응답을 받아 merchantPayKey가 다르면 실패 처리하고 취소하지 않는다")
	@Test
	void approve_whenForeignPgPaymentIdReturnsDifferentMerchantPayKey_failAttemptWithoutCancel() {
		// given
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-10-2", 1000);
		given(naverPayGateway.approve("pg-foreign-10-2"))
			.willReturn(NaverPayApproveResult.success("OTHER-PAY", 1000));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-10-2", "pg-foreign-10-2"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_MERCHANT_KEY_MISMATCH));
		assertThat(paymentPersistence.findPaymentByMerchantPayKey("PAY-INT-10-2")).isEmpty();
		assertThat(getAttempt("PAY-INT-10-2", "pg-foreign-10-2", PaymentAttemptType.APPROVE).getStatus())
			.isEqualTo(PaymentAttemptStatus.FAILED);
		assertCancelAttemptEmpty("PAY-INT-10-2", "pg-foreign-10-2");
		then(naverPayGateway).should(never()).cancel(any(), anyInt(), any());
	}

	@DisplayName("다른 사용자의 pgPaymentId로 AlreadyComplete 응답을 받았고 history merchantPayKey가 다르면 실패 처리하고 취소하지 않는다")
	@Test
	void approve_whenForeignPgPaymentIdHistoryReturnsDifferentMerchantPayKey_failAttemptWithoutCancel() {
		// given
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-10-3", 1000);
		given(naverPayGateway.approve("pg-foreign-10-3"))
			.willReturn(NaverPayApproveResult.alreadyComplete());
		given(naverPayGateway.getApprovalHistory("pg-foreign-10-3"))
			.willReturn(NaverPayHistoryResult.approved("OTHER-PAY", 1000));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-10-3", "pg-foreign-10-3"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND));
		assertThat(paymentPersistence.findPaymentByMerchantPayKey("PAY-INT-10-3")).isEmpty();
		assertThat(getAttempt("PAY-INT-10-3", "pg-foreign-10-3", PaymentAttemptType.APPROVE).getStatus())
			.isEqualTo(PaymentAttemptStatus.FAILED);
		assertCancelAttemptEmpty("PAY-INT-10-3", "pg-foreign-10-3");
		then(naverPayGateway).should(never()).cancel(any(), anyInt(), any());
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
	@DisplayName("approve attempt가 SUCCEEDED인데 payment가 없으면 상태 전이 불가 예외를 던진다")
	@Test
	void approve_whenAttemptSucceededAndPaymentMissing_throwException() {
		// given
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-12-1", 1000);
		PaymentAttempt attempt = PaymentAttempt.createApproveRequested(
			"PAY-INT-12-1", "pg-int-12-1", 1000, PaymentProvider.NAVERPAY
		);
		attempt.succeed(LocalDateTime.now());
		paymentPersistence.save(attempt);
		given(naverPayGateway.getApprovalHistory("pg-int-12-1"))
			.willReturn(NaverPayHistoryResult.approved("PAY-INT-12-1", 1000));
		// succeedApproveAttempt throw → failApproveAndCancelApprovedPayment 경로로 PG cancel 시도
		given(naverPayGateway.cancel(any(), anyInt(), any()))
			.willReturn(NaverPayCancelResult.success());

		// when & then
		// attempt SUCCEEDED + payment 없음은 정상 트랜잭션 경계에서 만들어질 수 없는 데이터 오염 상태다.
		// 조용히 복구하지 않고 500으로 터뜨려 운영팀이 원인을 조사하도록 한다.
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-12-1", "pg-int-12-1"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_ATTEMPT_STATUS_TRANSITION_NOT_ALLOWED));
		assertThat(paymentPersistence.findPaymentByMerchantPayKey("PAY-INT-12-1")).isEmpty();
	}

	private Member createMember() {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		return Member.builder()
			.email("naverpay-int-" + suffix + "@example.com")
			.password("password123")
			.username("u" + suffix)
			.build();
	}

	private Order persistOrder(Member member, String merchantPayKey, int totalPrice) {
		Product product = productPersistence.save(createProduct("product-" + merchantPayKey, totalPrice));
		return orderPersistence.saveAndFlush(createOrder(member, product, merchantPayKey));
	}

	private Product createProduct(String name, int price) {
		return Product.builder()
			.name(name)
			.price(price)
			.status(ProductStatus.ON_SALE)
			.build();
	}

	private Order createOrder(Member member, Product product, String merchantPayKey) {
		Order order = Order.create(member.getId());
		order.addOrderItem(product.getId(), 1, product.getPrice());
		order.assignMerchantPayKey(merchantPayKey);
		return order;
	}

	private PaymentAttempt getAttempt(String merchantPayKey, String pgPaymentId, PaymentAttemptType type) {
		return paymentPersistence.getAttempt(
			merchantPayKey,
			PaymentProvider.NAVERPAY,
			pgPaymentId,
			type
		);
	}

	private void assertCancelAttemptEmpty(String merchantPayKey, String pgPaymentId) {
		assertThat(paymentPersistence.findAttempt(
			merchantPayKey, PaymentProvider.NAVERPAY, pgPaymentId, PaymentAttemptType.CANCEL
		)).isEmpty();
	}

}
