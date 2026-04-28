package com.commerce.payment.naverpay.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;

import com.commerce.payment.naverpay.client.request.NaverPayCancelRequest;
import com.commerce.payment.naverpay.client.request.NaverPayCancelRequester;
import com.commerce.payment.naverpay.client.response.NaverPayResponse;
import com.commerce.payment.naverpay.client.response.body.NaverPayApproveBody;
import com.commerce.payment.naverpay.client.response.body.NaverPayCancelBody;
import com.commerce.payment.naverpay.client.response.body.NaverPayHistoryBody;
import com.commerce.payment.naverpay.config.NaverPayClientConfig;
import com.commerce.payment.naverpay.config.NaverPayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

@Tag("sandbox")
@SpringBootTest(classes = {
	NaverPayClient.class,
	NaverPayProperties.class,
	NaverPayClientConfig.class,
	NaverPayClientIntegrationTest.TestConfig.class
})
@TestPropertySource(properties = {
	"naverpay.client-id=${LOCAL_NAVERPAY_CLIENT_ID:}",
	"naverpay.client-secret=${LOCAL_NAVERPAY_CLIENT_SECRET:}",
	"naverpay.chain-id=${LOCAL_NAVERPAY_CHAIN_ID:}",
	"naverpay.return-url=${LOCAL_NAVERPAY_RETURN_URL:https://example.com/naverpay/return}",
	"naverpay.approval-url=${LOCAL_NAVERPAY_APPROVAL_URL:https://dev-pay.paygate.naver.com/naverpay-partner/naverpay/payments/v2.2/apply/payment}",
	"naverpay.cancel-url=${LOCAL_NAVERPAY_CANCEL_URL:https://dev-pay.paygate.naver.com/naverpay-partner/naverpay/payments/v1/cancel}",
	"naverpay.payment-history-url=${LOCAL_NAVERPAY_PAYMENT_HISTORY_URL:https://dev-pay.paygate.naver.com/naverpay-partner/naverpay/payments/v2.2/list/history/{paymentId}}"
})
class NaverPayClientIntegrationTest {

	private static final String ENABLE_CANCEL = "true";

	/*
	 * 네이버페이 샌드박스 테스트 운영 메모
	 *
	 * 필수 .env.sandbox 값:
	 * - LOCAL_NAVERPAY_CLIENT_ID
	 * - LOCAL_NAVERPAY_CLIENT_SECRET
	 * - LOCAL_NAVERPAY_CHAIN_ID
	 *
	 * 테스트별 선택 값:
	 * - LOCAL_NAVERPAY_APPROVE_PAYMENT_ID: 승인 API 테스트에 사용할 paymentId
	 * - LOCAL_NAVERPAY_CANCEL_PAYMENT_ID: 취소 API 테스트에 사용할 paymentId
	 * - LOCAL_NAVERPAY_HISTORY_PAYMENT_ID: 이력 조회 API 테스트에 사용할 paymentId
	 * - LOCAL_NAVERPAY_ENABLE_CANCEL=true: 취소 API 테스트 실행 허용
	 *
	 * 샌드박스 paymentId는 주기적으로 만료되거나 초기화될 수 있다.
	 * 그래서 코드에 고정하지 말고 .env.sandbox에서만 관리한다.
	 * 필요한 값이 없으면 테스트 실패가 아니라 JUnit assumption으로 해당 테스트만 skip된다.
	 *
	 * 관찰된 응답 형태:
	 * - 성공/실패 응답은 공통적으로 { code, message, body } 형태로 매핑된다.
	 * - 이미 승인 완료된 결제를 다시 승인 요청하면 code=AlreadyComplete, body=null 응답이 올 수 있다.
	 * - 승인 가능 시간이 만료된 결제를 승인 요청하면 code=TimeExpired, body=null 응답이 올 수 있다.
	 * - 승인/취소/이력 조회 성공 응답은 API별로 body 상세 내용이 달라진다.
	 */

	@TestConfiguration
	static class TestConfig {
		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper();
		}
	}

	@Autowired
	private NaverPayClient naverPayClient;

	@DisplayName("샌드박스 승인 API 호출이 성공하면 응답이 매핑된다")
	@Test
	void approve_whenSandboxConfigured_mapResponse() {
		assumeSandboxConfigured();
		String paymentId = approvePaymentId();
		assumeTrue(isPresent(paymentId));

		NaverPayResponse<NaverPayApproveBody> response = naverPayClient.approve(paymentId);

		assertThat(response).isNotNull();
		assertThat(response.getCode()).isNotBlank();

		printResponse(response);

		if (response.getBody() != null) {
			NaverPayApproveBody.Detail detail = response.getBody().getDetail();
			assertThat(detail).isNotNull();
		}
	}

	@DisplayName("샌드박스 취소 API 호출이 성공하면 응답이 매핑된다")
	@Test
	void cancel_whenSandboxConfigured_mapResponse() {
		assumeSandboxConfigured();
		assumeTrue(ENABLE_CANCEL.equalsIgnoreCase(System.getenv("LOCAL_NAVERPAY_ENABLE_CANCEL")));
		String paymentId = cancelPaymentId();
		assumeTrue(isPresent(paymentId));

		NaverPayCancelRequest request = NaverPayCancelRequest.builder()
			.paymentId(paymentId)
			.cancelAmount(1000)
			.cancelReason("승인 금액 불일치")
			.cancelRequester(NaverPayCancelRequester.CANCEL_BY_ADMIN)
			.taxScopeAmount(1000)
			.taxExScopeAmount(0)
			.build();
		NaverPayResponse<NaverPayCancelBody> response = naverPayClient.cancel(request);

		assertThat(response).isNotNull();
		assertThat(response.getCode()).isNotBlank();
		printResponse(response);
	}

	@DisplayName("샌드박스 승인 이력 조회 API 호출이 성공하면 응답이 매핑된다")
	@Test
	void getApprovalHistory_whenSandboxConfigured_mapResponse() {
		assumeSandboxConfigured();
		String paymentId = historyPaymentId();
		assumeTrue(isPresent(paymentId));

		NaverPayResponse<NaverPayHistoryBody> response = naverPayClient.getApprovalHistory(paymentId);

		assertThat(response).isNotNull();
		assertThat(response.getCode()).isNotBlank();
		printResponse(response);
	}

	@DisplayName("샌드박스 취소 이력 조회 API 호출이 성공하면 응답이 매핑된다")
	@Test
	void getCancelHistory_whenSandboxConfigured_mapResponse() {
		assumeSandboxConfigured();
		String paymentId = historyPaymentId();
		assumeTrue(isPresent(paymentId));

		NaverPayResponse<NaverPayHistoryBody> response = naverPayClient.getCancelHistory(paymentId);

		assertThat(response).isNotNull();
		assertThat(response.getCode()).isNotBlank();
		printResponse(response);
	}

	@DisplayName("샌드박스 전체 이력 조회 API 호출이 성공하면 응답이 매핑된다")
	@Test
	void getAllHistory_whenSandboxConfigured_mapResponse() {
		assumeSandboxConfigured();
		String paymentId = historyPaymentId();
		assumeTrue(isPresent(paymentId));

		NaverPayResponse<NaverPayHistoryBody> response = naverPayClient.getAllHistory(paymentId);

		assertThat(response).isNotNull();
		assertThat(response.getCode()).isNotBlank();
		printResponse(response);
	}

	private void assumeSandboxConfigured() {
		assumeTrue(isPresent(System.getenv("LOCAL_NAVERPAY_CLIENT_ID")));
		assumeTrue(isPresent(System.getenv("LOCAL_NAVERPAY_CLIENT_SECRET")));
		assumeTrue(isPresent(System.getenv("LOCAL_NAVERPAY_CHAIN_ID")));
	}

	private String approvePaymentId() {
		return System.getenv("LOCAL_NAVERPAY_APPROVE_PAYMENT_ID");
	}

	private String cancelPaymentId() {
		return System.getenv("LOCAL_NAVERPAY_CANCEL_PAYMENT_ID");
	}

	private String historyPaymentId() {
		return System.getenv("LOCAL_NAVERPAY_HISTORY_PAYMENT_ID");
	}

	private boolean isPresent(String value) {
		return value != null && !value.isBlank();
	}

	private void printResponse(Object response) {
		try {
			ObjectMapper objectMapper = new ObjectMapper();
			String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(response);
			System.out.println(json);
		} catch (Exception e) {
			System.out.println(response);
		}
	}
}
