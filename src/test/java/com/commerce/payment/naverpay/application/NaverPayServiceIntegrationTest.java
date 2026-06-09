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
import com.commerce.payment.domain.PaymentFailCode;
import com.commerce.payment.domain.PaymentProvider;
import com.commerce.payment.domain.PaymentReservation;
import com.commerce.payment.domain.PaymentStatus;
import com.commerce.payment.domain.PaymentType;
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
import com.commerce.payment.application.PaymentCancellationService;
import com.commerce.payment.infrastructure.persistence.support.PaymentPersistenceTestSupport;
import com.commerce.payment.infrastructure.persistence.support.PaymentReservationPersistenceTestSupport;
import com.commerce.member.infrastructure.persistence.support.MemberPersistenceTestSupport;
import com.commerce.order.infrastructure.persistence.support.OrderPersistenceTestSupport;
import com.commerce.product.domain.Product;
import com.commerce.product.domain.ProductStatus;
import com.commerce.product.infrastructure.persistence.support.ProductPersistenceTestSupport;
import com.commerce.support.PersistenceCleanupTestSupport;

@SpringBootTest
@ActiveProfiles("test")
@Import({PersistenceCleanupTestSupport.class, PaymentPersistenceTestSupport.class, PaymentReservationPersistenceTestSupport.class, MemberPersistenceTestSupport.class, ProductPersistenceTestSupport.class, OrderPersistenceTestSupport.class})
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

	@Autowired
	private PaymentReservationPersistenceTestSupport reservationPersistence;

	@MockitoBean
	private NaverPayGateway naverPayGateway;

	@MockitoSpyBean
	private PaymentApprovalService paymentApprovalService;

	// succeed / fail 강제 예외 주입 시나리오용 spy.
	// find-first 리팩토링 이후 H2 우회용 getOrCreate* doReturn 스텁은 모두 제거됐다.
	@MockitoSpyBean
	private PaymentCancellationService paymentCancellationService;

	@Autowired
	private PersistenceCleanupTestSupport persistenceCleanup;

	@AfterEach
	void tearDown() {
		Mockito.reset(naverPayGateway, paymentApprovalService, paymentCancellationService);
		persistenceCleanup.deleteAllInBatch(
			paymentPersistence, reservationPersistence, memberPersistence, productPersistence, orderPersistence
		);
	}

	/**
	 * ===================================================
	 * 1. 정상 승인
	 * ===================================================
	 */
	@DisplayName("승인이 성공하면 payment를 생성하고 order를 PAID로 변경하며 approve payment를 SUCCEEDED로 저장한다")
	@Test
	void approve_whenSuccess_createPaymentAndMarkOrderPaidAndSucceedPayment() {
		// given
		Member member = memberPersistence.save(createMember());
		Order order = persistOrder(member, "PAY-INT-1", 1000);
		given(naverPayGateway.approve("pg-int-1"))
			.willReturn(NaverPayApproveResult.success("PAY-INT-1", 1000));

		// when
		NaverPayApproveResponse result = naverPayApprovalService.approve(member.getId(), "PAY-INT-1", "pg-int-1");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.SUCCESS);
		assertThat(result.getPgPaymentId()).isEqualTo("pg-int-1");
		assertThat(paymentPersistence.findApproveSucceeded("PAY-INT-1")).isPresent();
		assertThat(orderPersistence.getOrderStatusById(order.getId())).isEqualTo(OrderStatus.PAID);
		assertThat(getPayment("PAY-INT-1", "pg-int-1", PaymentType.APPROVE).getStatus())
			.isEqualTo(PaymentStatus.SUCCEEDED);
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
		assertThat(paymentPersistence.findApproveSucceeded("PAY-INT-2-1")).isEmpty();
		assertThat(getPayment("PAY-INT-2-1", "pg-int-2-1", PaymentType.APPROVE).getStatus())
			.isEqualTo(PaymentStatus.REQUESTED);
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
		assertThat(paymentPersistence.findApproveSucceeded("PAY-INT-2-2")).isPresent();
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

	@DisplayName("AlreadyComplete 응답 이후 history가 취소 완료 상태면 approve payment를 ALREADY_CANCELED로 실패 처리하고 PAYMENT_ALREADY_CANCELED를 던진다")
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
		assertThat(getPayment("PAY-INT-3-2-C", "pg-int-3-2-c", PaymentType.APPROVE).getStatus())
			.isEqualTo(PaymentStatus.FAILED);
		assertThat(getPayment("PAY-INT-3-2-C", "pg-int-3-2-c", PaymentType.APPROVE).getFailCode())
			.isEqualTo(PaymentFailCode.ALREADY_CANCELED);
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
	@DisplayName("approve 응답 merchantPayKey가 다르면 approve payment를 FAILED로 저장하고 취소는 요청하지 않는다")
	@Test
	void approve_whenApproveMerchantPayKeyMismatch_failPaymentWithoutCancel() {
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
		assertThat(getPayment("PAY-INT-4-1", "pg-int-4-1", PaymentType.APPROVE).getStatus())
			.isEqualTo(PaymentStatus.FAILED);
		assertCancelPaymentEmpty("PAY-INT-4-1", "pg-int-4-1");
		then(naverPayGateway).should(never()).cancel(any(), anyInt(), any());
	}

	@DisplayName("history 경로 merchantPayKey가 다르면 approve payment를 FAILED로 저장하고 취소는 요청하지 않는다")
	@Test
	void approve_whenHistoryMerchantPayKeyMismatch_failPaymentWithoutCancel() {
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
		assertThat(getPayment("PAY-INT-4-2", "pg-int-4-2", PaymentType.APPROVE).getStatus())
			.isEqualTo(PaymentStatus.FAILED);
		assertCancelPaymentEmpty("PAY-INT-4-2", "pg-int-4-2");
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
		assertThat(getPayment("PAY-INT-5-1", "pg-int-5-1", PaymentType.CANCEL).getStatus())
			.isEqualTo(PaymentStatus.SUCCEEDED);
	}

	@DisplayName("금액 불일치에서 cancel 응답이 AlreadyCanceled면 cancel payment를 SUCCEEDED로 저장한다")
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
		assertThat(getPayment("PAY-INT-5-2", "pg-int-5-2", PaymentType.CANCEL).getStatus())
			.isEqualTo(PaymentStatus.SUCCEEDED);
	}

	@DisplayName("금액 불일치에서 cancel 응답이 AlreadyOnGoing이면 cancel payment를 REQUESTED로 유지한다")
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
		assertThat(getPayment("PAY-INT-5-3", "pg-int-5-3", PaymentType.CANCEL).getStatus())
			.isEqualTo(PaymentStatus.REQUESTED);
	}

	@DisplayName("금액 불일치에서 cancel 응답이 Fail이면 cancel payment를 FAILED로 저장한다")
	@Test
	void approve_whenAmountMismatchAndCancelFail_markCancelFailed() {
		// given
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-5-4", 1000);
		given(naverPayGateway.approve("pg-int-5-4"))
			.willReturn(NaverPayApproveResult.success("PAY-INT-5-4", 2000));
		given(naverPayGateway.cancel(any(), anyInt(), any()))
			.willReturn(NaverPayCancelResult.failed(PaymentFailCode.PG_REQUEST_REJECTED, "기타 실패"));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-5-4", "pg-int-5-4"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH));
		assertThat(getPayment("PAY-INT-5-4", "pg-int-5-4", PaymentType.CANCEL).getStatus())
			.isEqualTo(PaymentStatus.FAILED);
	}

	/**
	 * ===================================================
	 * 6. 중복 결제
	 * ===================================================
	 */
	@DisplayName("USED Reservation에 같은 merchantPayKey redirect가 중복 도착하면 기존 결제 결과를 반환한다 (멱등 응답)")
	@Test
	void approve_whenPaymentAlreadyExists_returnExistingPayment() {
		// given
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-6-1", 1000);
		PaymentReservation reservation = reservationPersistence.findByMerchantPayKey("PAY-INT-6-1").orElseThrow();
		// USED: 이미 한 번 approve 흐름을 통과한 상태 (같은 pgPaymentId로 redirect 중복 도착 시나리오)
		reservation.use();
		reservationPersistence.save(reservation);
		Payment existingPayment = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-int-6-1");
		existingPayment.succeed(LocalDateTime.now());
		paymentPersistence.save(existingPayment);

		// when
		NaverPayApproveResponse result = naverPayApprovalService.approve(member.getId(), "PAY-INT-6-1", "pg-int-6-1");

		// then: 멱등 200 응답 — PG 호출 0회
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.SUCCESS);
		assertThat(result.getPgPaymentId()).isEqualTo("pg-int-6-1");
		then(naverPayGateway).should(never()).approve(any());
	}

	@DisplayName("succeedApproval에서 PAYMENT_DUPLICATE가 발생하면 현재 승인건 취소를 요청한다")
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
			.succeedApproval(any(Payment.class), any(LocalDateTime.class));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-6-2", "pg-int-6-2"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_DUPLICATE));
		assertThat(getPayment("PAY-INT-6-2", "pg-int-6-2", PaymentType.APPROVE).getStatus())
			.isEqualTo(PaymentStatus.FAILED);
		assertThat(getPayment("PAY-INT-6-2", "pg-int-6-2", PaymentType.APPROVE).getFailCode())
			.isEqualTo(PaymentFailCode.DUPLICATE_PAYMENT);
		assertThat(getPayment("PAY-INT-6-2", "pg-int-6-2", PaymentType.CANCEL).getStatus())
			.isEqualTo(PaymentStatus.SUCCEEDED);
	}

	@DisplayName("이미 취소 완료된 시도가 있으면 succeedApproval 중복 예외에서도 취소를 다시 요청하지 않는다")
	@Test
	void approve_whenCompleteApproveThrowsDuplicateAndCancelAlreadySucceeded_skipCancelRequest() {
		// given
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-6-3", 1000);
		Payment cancelPayment = Payment.createCancelRequested(
			1L, "PAY-INT-6-3", "pg-int-6-3", 1000, PaymentProvider.NAVERPAY
		);
		cancelPayment.succeed(LocalDateTime.now());
		paymentPersistence.save(cancelPayment);

		given(naverPayGateway.approve("pg-int-6-3"))
			.willReturn(NaverPayApproveResult.success("PAY-INT-6-3", 1000));
		Mockito.doThrow(new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE))
			.when(paymentApprovalService)
			.succeedApproval(any(Payment.class), any(LocalDateTime.class));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-6-3", "pg-int-6-3"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_DUPLICATE));
		assertThat(getPayment("PAY-INT-6-3", "pg-int-6-3", PaymentType.CANCEL).getStatus())
			.isEqualTo(PaymentStatus.SUCCEEDED);
		then(naverPayGateway).should(never()).cancel(any(), anyInt(), any());
	}

	@DisplayName("succeedApproval에서 PAYMENT_DUPLICATE가 발생하고 취소가 실패하면 cancel payment를 FAILED로 저장한다")
	@Test
	void approve_whenCompleteApproveThrowsDuplicateAndCancelFail_markCancelFailed() {
		// given
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-6-4", 1000);
		given(naverPayGateway.approve("pg-int-6-4"))
			.willReturn(NaverPayApproveResult.success("PAY-INT-6-4", 1000));
		given(naverPayGateway.cancel(any(), anyInt(), any()))
			.willReturn(NaverPayCancelResult.failed(PaymentFailCode.PG_REQUEST_REJECTED, "기타 실패"));
		Mockito.doThrow(new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE))
			.when(paymentApprovalService)
			.succeedApproval(any(Payment.class), any(LocalDateTime.class));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-6-4", "pg-int-6-4"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_DUPLICATE));
		assertThat(getPayment("PAY-INT-6-4", "pg-int-6-4", PaymentType.APPROVE).getStatus())
			.isEqualTo(PaymentStatus.FAILED);
		assertThat(getPayment("PAY-INT-6-4", "pg-int-6-4", PaymentType.APPROVE).getFailCode())
			.isEqualTo(PaymentFailCode.DUPLICATE_PAYMENT);
		assertThat(getPayment("PAY-INT-6-4", "pg-int-6-4", PaymentType.CANCEL).getStatus())
			.isEqualTo(PaymentStatus.FAILED);
	}

	@DisplayName("이미 취소 진행 중인 시도가 있으면 succeedApproval 중복 예외에서도 cancel payment를 REQUESTED로 유지한다")
	@Test
	void approve_whenCompleteApproveThrowsDuplicateAndCancelAlreadyOnGoing_keepCancelRequested() {
		// given
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-6-5", 1000);
		paymentPersistence.save(
			Payment.createCancelRequested(1L, "PAY-INT-6-5", "pg-int-6-5", 1000, PaymentProvider.NAVERPAY)
		);

		given(naverPayGateway.approve("pg-int-6-5"))
			.willReturn(NaverPayApproveResult.success("PAY-INT-6-5", 1000));
		given(naverPayGateway.cancel(any(), anyInt(), any()))
			.willReturn(NaverPayCancelResult.processing());
		Mockito.doThrow(new PaymentException(PaymentErrorCode.PAYMENT_DUPLICATE))
			.when(paymentApprovalService)
			.succeedApproval(any(Payment.class), any(LocalDateTime.class));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-6-5", "pg-int-6-5"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_DUPLICATE));
		assertThat(getPayment("PAY-INT-6-5", "pg-int-6-5", PaymentType.CANCEL).getStatus())
			.isEqualTo(PaymentStatus.REQUESTED);
	}

	@DisplayName("형제 pgPaymentId(pgA)가 이미 SUCCEEDED인 상태에서 pgB 승인 시도는 진입 가드에서 PG 호출 전 차단된다")
	@Test
	void approve_whenDuplicatePaymentWithSucceededSibling_blockedByEntryGuardBeforePgCall() {
		// given: 동일 merchantPayKey로 pgA가 먼저 SUCCEEDED — 형제 결제 성공 상태
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-6-6", 1000);
		PaymentReservation reservation = reservationPersistence.findByMerchantPayKey("PAY-INT-6-6").orElseThrow();
		Payment pgA = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-int-6-6-a");
		pgA.succeed(LocalDateTime.now());
		paymentPersistence.save(pgA);

		// when & then: pgB는 existsApprovedByOrderId 가드에서 PG 호출 전 차단된다 (ADR-L2)
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-6-6", "pg-int-6-6-b"))
			.isInstanceOfSatisfying(PaymentException.class, exception ->
				assertThat(exception.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_DUPLICATE));

		// pgB: PG 호출 전 차단 — approve/cancel 모두 호출되지 않음
		then(naverPayGateway).should(never()).approve(any());
		then(naverPayGateway).should(never()).cancel(any(), anyInt(), any());

		// pgB payment: 생성되지 않음 (create() 호출 전 차단)
		assertCancelPaymentEmpty("PAY-INT-6-6", "pg-int-6-6-b");

		// pgA: SUCCEEDED 보존 — 가드가 형제 결제를 건드리지 않음
		assertThat(getPayment("PAY-INT-6-6", "pg-int-6-6-a", PaymentType.APPROVE).getStatus())
			.isEqualTo(PaymentStatus.SUCCEEDED);
	}

	/**
	 * ===================================================
	 * 7. payment 상태 분기
	 * ===================================================
	 */
	@DisplayName("approve payment가 FAILED면 PG 호출 없이 즉시 예외를 던진다")
	@Test
	void approve_whenPaymentAlreadyFailed_throwImmediatelyWithoutPgCall() {
		// given
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-7-1", 1000);

		PaymentReservation reservation = reservationPersistence.findByMerchantPayKey("PAY-INT-7-1").orElseThrow();
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-int-7-1");
		payment.fail(PaymentFailCode.TIME_EXPIRED, "expired", LocalDateTime.now());
		paymentPersistence.save(payment);

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
	void approve_whenNetworkError_markApprovePaymentFailed() {
		// given
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-8-1", 1000);
		given(naverPayGateway.approve("pg-int-8-1"))
			.willReturn(NaverPayApproveResult.failed(
				PaymentFailCode.PG_NETWORK_ERROR, PaymentErrorCode.PAYMENT_PG_NETWORK_ERROR, "network error"));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-8-1", "pg-int-8-1"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_PG_NETWORK_ERROR));
		assertThat(getPayment("PAY-INT-8-1", "pg-int-8-1", PaymentType.APPROVE).getFailCode())
			.isEqualTo(PaymentFailCode.PG_NETWORK_ERROR);
	}

	@DisplayName("approve 호출에서 서버 오류가 발생하면 PG_SERVER_ERROR로 실패 처리한다")
	@Test
	void approve_whenServerError_markApprovePaymentFailed() {
		// given
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-8-2", 1000);
		given(naverPayGateway.approve("pg-int-8-2"))
			.willReturn(NaverPayApproveResult.failed(
				PaymentFailCode.PG_SERVER_ERROR, PaymentErrorCode.PAYMENT_PG_SERVER_ERROR, "server error"));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-8-2", "pg-int-8-2"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_PG_SERVER_ERROR));
		assertThat(getPayment("PAY-INT-8-2", "pg-int-8-2", PaymentType.APPROVE).getFailCode())
			.isEqualTo(PaymentFailCode.PG_SERVER_ERROR);
	}

	@DisplayName("approve 성공 응답인데 body가 비어있으면 INVALID_RESPONSE 예외가 발생한다")
	@Test
	void approve_whenApproveResponseBodyIsNull_throwInvalidResponseException() {
		// given
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-8-3", 1000);
		given(naverPayGateway.approve("pg-int-8-3"))
			.willReturn(NaverPayApproveResult.failed(
				PaymentFailCode.PG_INVALID_RESPONSE,
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
				PaymentFailCode.PG_MAINTENANCE, PaymentErrorCode.PAYMENT_PG_MAINTENANCE, "서비스 점검중"));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-8-4", "pg-int-8-4"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_PG_MAINTENANCE));
		assertThat(getPayment("PAY-INT-8-4", "pg-int-8-4", PaymentType.APPROVE).getFailCode())
			.isEqualTo(PaymentFailCode.PG_MAINTENANCE);
	}

	/**
	 * ===================================================
	 * 9. DB/서버 장애
	 * ===================================================
	 */
	@DisplayName("succeedApproval 중 CustomException(주문 상태 예외)이 발생하면 보상 없이 예외를 전파하고 approve는 REQUESTED로 남는다")
	@Test
	void approve_whenOrderAlreadyPaid_propagatesWithoutCompensation() {
		// given: PG SUCCESS 후 order 상태 예외(CustomException) — transient/정상 반영 실패
		// 정상 매출을 취소하지 않고 예외 전파, approve REQUESTED 유지 → reconcile self-heal (ADR-L1)
		Member member = memberPersistence.save(createMember());
		Order order = persistOrder(member, "PAY-INT-9-1", 1000);
		order.completePayment();
		orderPersistence.save(order);

		given(naverPayGateway.approve("pg-int-9-1"))
			.willReturn(NaverPayApproveResult.success("PAY-INT-9-1", 1000));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-9-1", "pg-int-9-1"))
			.isInstanceOf(OrderException.class)
			.satisfies(exception -> assertThat(((OrderException)exception).getErrorCode())
				.isEqualTo(OrderErrorCode.ORDER_PAID_NOT_ALLOWED));
		// 보상(PG cancel)이 없으므로 CANCEL payment는 생성되지 않는다
		assertCancelPaymentEmpty("PAY-INT-9-1", "pg-int-9-1");
		// approve payment는 REQUESTED로 남아 reconcile self-heal 대상이 된다
		assertThat(getPayment("PAY-INT-9-1", "pg-int-9-1", PaymentType.APPROVE).getStatus())
			.isEqualTo(PaymentStatus.REQUESTED);
		then(naverPayGateway).should(never()).cancel(any(), anyInt(), any());
	}

	@DisplayName("succeedApproval에서 예상치 못한 예외(RuntimeException)가 발생하면 보상 없이 예외를 전파하고 approve는 REQUESTED로 남는다")
	@Test
	void approve_whenCompleteApproveThrowsUnexpectedException_propagatesWithoutCompensation() {
		// given: PG SUCCESS 후 DB 기록 실패(transient 또는 버그) — UNKNOWN/FAILED 둔갑 없이 전파(500)
		// approve REQUESTED 유지 → reconcile self-heal (ADR-L1)
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-9-2", 1000);
		given(naverPayGateway.approve("pg-int-9-2"))
			.willReturn(NaverPayApproveResult.success("PAY-INT-9-2", 1000));
		Mockito.doThrow(new RuntimeException("db write failed"))
			.when(paymentApprovalService)
			.succeedApproval(any(Payment.class), any(LocalDateTime.class));

		// when & then
		assertThatThrownBy(() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-9-2", "pg-int-9-2"))
			.isInstanceOf(RuntimeException.class)
			.hasMessage("db write failed");
		// 보상(PG cancel)이 없으므로 CANCEL payment는 생성되지 않는다
		assertCancelPaymentEmpty("PAY-INT-9-2", "pg-int-9-2");
		// approve payment는 REQUESTED로 남아 reconcile self-heal 대상이 된다 (FAILED/APPROVE_PROCESS_FAILED 박제 없음)
		assertThat(getPayment("PAY-INT-9-2", "pg-int-9-2", PaymentType.APPROVE).getStatus())
			.isEqualTo(PaymentStatus.REQUESTED);
		then(naverPayGateway).should(never()).cancel(any(), anyInt(), any());
	}

	@DisplayName("취소 API는 성공했지만 취소 성공 반영이 실패해도 원래 승인 실패 예외를 유지한다")
	@Test
	void approve_whenCancelSuccessButSucceedCancelPaymentFails_keepOriginalException() {
		// given
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-9-2", 1000);
		given(naverPayGateway.approve("pg-int-9-2"))
			.willReturn(NaverPayApproveResult.success("PAY-INT-9-2", 2000));
		given(naverPayGateway.cancel(any(), anyInt(), any()))
			.willReturn(NaverPayCancelResult.success());
		Mockito.doThrow(new PaymentException(PaymentErrorCode.PAYMENT_RECORD_NOT_FOUND))
			.when(paymentCancellationService)
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
	void approve_whenCancelFailAndFailCancelPaymentFails_keepOriginalException() {
		// given
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-9-3", 1000);
		given(naverPayGateway.approve("pg-int-9-3"))
			.willReturn(NaverPayApproveResult.success("PAY-INT-9-3", 2000));
		given(naverPayGateway.cancel(any(), anyInt(), any()))
			.willReturn(NaverPayCancelResult.failed(PaymentFailCode.PG_REQUEST_REJECTED, "기타 실패"));
		Mockito.doThrow(new PaymentException(PaymentErrorCode.PAYMENT_RECORD_NOT_FOUND))
			.when(paymentCancellationService)
			.fail(
				eq("PAY-INT-9-3"),
				eq(PaymentProvider.NAVERPAY),
				eq("pg-int-9-3"),
				eq(PaymentFailCode.PG_REQUEST_REJECTED),
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
	@DisplayName("다른 회원이 merchantPayKey로 승인 요청하면 PAYMENT_RESERVATION_NOT_FOUND가 발생한다 (키 존재 비노출)")
	@Test
	void approve_whenMemberDoesNotOwnReservation_throwPaymentReservationNotFound() {
		// given
		Member owner = memberPersistence.save(createMember());
		Member attacker = memberPersistence.save(createMember());
		persistOrder(owner, "PAY-INT-10-1", 1000);

		// when & then: (attacker.id, "PAY-INT-10-1") 조합이 없으므로 RESERVATION_NOT_FOUND (ADR-L3)
		assertThatThrownBy(() -> naverPayApprovalService.approve(attacker.getId(), "PAY-INT-10-1", "pg-int-10-1"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_RESERVATION_NOT_FOUND));
	}

	@DisplayName("다른 사용자의 pgPaymentId로 승인 응답을 받아 merchantPayKey가 다르면 실패 처리하고 취소하지 않는다")
	@Test
	void approve_whenForeignPgPaymentIdReturnsDifferentMerchantPayKey_failPaymentWithoutCancel() {
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
		assertThat(paymentPersistence.findApproveSucceeded("PAY-INT-10-2")).isEmpty();
		assertThat(getPayment("PAY-INT-10-2", "pg-foreign-10-2", PaymentType.APPROVE).getStatus())
			.isEqualTo(PaymentStatus.FAILED);
		assertCancelPaymentEmpty("PAY-INT-10-2", "pg-foreign-10-2");
		then(naverPayGateway).should(never()).cancel(any(), anyInt(), any());
	}

	@DisplayName("다른 사용자의 pgPaymentId로 AlreadyComplete 응답을 받았고 history merchantPayKey가 다르면 실패 처리하고 취소하지 않는다")
	@Test
	void approve_whenForeignPgPaymentIdHistoryReturnsDifferentMerchantPayKey_failPaymentWithoutCancel() {
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
		assertThat(paymentPersistence.findApproveSucceeded("PAY-INT-10-3")).isEmpty();
		assertThat(getPayment("PAY-INT-10-3", "pg-foreign-10-3", PaymentType.APPROVE).getStatus())
			.isEqualTo(PaymentStatus.FAILED);
		assertCancelPaymentEmpty("PAY-INT-10-3", "pg-foreign-10-3");
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
	@DisplayName("USED 예약에 같은 pgPaymentId로 재진입 시 이미 SUCCEEDED인 결제를 PG 호출 없이 반환한다")
	@Test
	void approve_whenPaymentAlreadySucceeded_returnSuccessDirectly() {
		// given
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-12-1", 1000);
		PaymentReservation reservation = reservationPersistence.findByMerchantPayKey("PAY-INT-12-1").orElseThrow();
		Payment payment = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-int-12-1");
		payment.succeed(LocalDateTime.now());
		paymentPersistence.save(payment);
		// USED: 실제 흐름에서 create()가 reservation.use()·saveUsed()를 완료한 상태 (redirect 재진입 시나리오)
		reservation.use();
		reservationPersistence.save(reservation);

		// when
		NaverPayApproveResponse result = naverPayApprovalService.approve(member.getId(), "PAY-INT-12-1", "pg-int-12-1");

		// then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveStatus.SUCCESS);
		assertThat(result.getPgPaymentId()).isEqualTo("pg-int-12-1");
		then(naverPayGateway).should(never()).approve(any());
		then(naverPayGateway).should(never()).getApprovalHistory(any());
	}

	/**
	 * ===================================================
	 * 13. 진입 차단 (ADR-L2)
	 * ===================================================
	 */
	@DisplayName("이미 APPROVE·SUCCEEDED 결제가 있는 주문에 새 승인 요청이 진입 단계에서 차단되고 PG 호출이 발생하지 않는다")
	@Test
	void approve_whenApprovedPaymentAlreadyExists_throwPaymentDuplicateBeforePgCall() {
		// given
		Member member = memberPersistence.save(createMember());
		persistOrder(member, "PAY-INT-13-1", 1000);
		PaymentReservation reservation = reservationPersistence.findByMerchantPayKey("PAY-INT-13-1").orElseThrow();

		// 동일 주문에 이미 APPROVE·SUCCEEDED payment 존재
		Payment existingPayment = Payment.createRequested(reservation, PaymentType.APPROVE, "pg-int-13-1-old");
		existingPayment.succeed(LocalDateTime.now());
		paymentPersistence.save(existingPayment);

		// when & then: 새 pgPaymentId로 승인 시도 → 진입 단계에서 PAYMENT_DUPLICATE 차단
		assertThatThrownBy(
			() -> naverPayApprovalService.approve(member.getId(), "PAY-INT-13-1", "pg-int-13-1-new"))
			.isInstanceOf(PaymentException.class)
			.satisfies(exception -> assertThat(((PaymentException)exception).getErrorCode())
				.isEqualTo(PaymentErrorCode.PAYMENT_DUPLICATE));
		then(naverPayGateway).should(never()).approve(any());
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
		Order order = orderPersistence.saveAndFlush(createOrder(member, product));
		reservationPersistence.save(
			PaymentReservation.createReserved(order.getId(), member.getId(), totalPrice, PaymentProvider.NAVERPAY,
				merchantPayKey, LocalDateTime.now().plusMinutes(15))
		);
		return order;
	}

	private Product createProduct(String name, int price) {
		return Product.builder()
			.name(name)
			.price(price)
			.status(ProductStatus.ON_SALE)
			.build();
	}

	private Order createOrder(Member member, Product product) {
		Order order = Order.create(member.getId());
		order.addOrderItem(product.getId(), 1, product.getPrice());
		return order;
	}

	private Payment getPayment(String merchantPayKey, String pgPaymentId, PaymentType type) {
		return paymentPersistence.getPayment(
			merchantPayKey,
			PaymentProvider.NAVERPAY,
			pgPaymentId,
			type
		);
	}

	private void assertCancelPaymentEmpty(String merchantPayKey, String pgPaymentId) {
		assertThat(paymentPersistence.findPayment(
			merchantPayKey, PaymentProvider.NAVERPAY, pgPaymentId, PaymentType.CANCEL
		)).isEmpty();
	}

}
