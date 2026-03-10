package com.commerce.payment.naverpay.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import com.commerce.payment.naverpay.client.request.NaverPayCancelRequest;
import com.commerce.payment.naverpay.client.request.NaverPayCancelRequester;
import com.commerce.payment.naverpay.client.response.NaverPayResponse;
import com.commerce.payment.naverpay.client.response.body.NaverPayApproveBody;
import com.commerce.payment.naverpay.client.response.body.NaverPayCancelBody;
import com.commerce.payment.naverpay.config.NaverPayProperties;
import com.commerce.payment.naverpay.exception.NaverPayErrorCode;
import com.commerce.payment.naverpay.exception.NaverPayException;
import com.fasterxml.jackson.databind.ObjectMapper;

class NaverPayClientTest {

	private static final String APPROVAL_URL = "https://test.naverpay/naverpay-partner/naverpay/payments/v2.2/apply/payment";
	private static final String CANCEL_URL = "https://test.naverpay/naverpay-partner/naverpay/payments/v1/cancel";
	private static final String PAYMENT_HISTORY_URL =
		"https://test.naverpay/naverpay-partner/naverpay/payments/v2.2/list/history/{paymentId}";
	private static final String RESOLVED_PAYMENT_HISTORY_URL =
		"https://test.naverpay/naverpay-partner/naverpay/payments/v2.2/list/history/pg-payment-id";

	private NaverPayClient client;
	private MockRestServiceServer server;

	@BeforeEach
	void setUp() {
		NaverPayProperties naverPayProperties = new NaverPayProperties();
		ReflectionTestUtils.setField(naverPayProperties, "clientId", "client-id");
		ReflectionTestUtils.setField(naverPayProperties, "clientSecret", "client-secret");
		ReflectionTestUtils.setField(naverPayProperties, "chainId", "chain-id");
		ReflectionTestUtils.setField(naverPayProperties, "approvalUrl", APPROVAL_URL);
		ReflectionTestUtils.setField(naverPayProperties, "cancelUrl", CANCEL_URL);
		ReflectionTestUtils.setField(naverPayProperties, "paymentHistoryUrl", PAYMENT_HISTORY_URL);
		RestTemplate restTemplate = new RestTemplate();
		ObjectMapper objectMapper = new ObjectMapper();

		client = new NaverPayClient(naverPayProperties, restTemplate, objectMapper);
		server = MockRestServiceServer.bindTo(restTemplate).build();
	}

	@DisplayName("승인 요청이 성공하면 응답이 매핑된다")
	@Test
	void approve_whenSuccess_mapResponse() {
		server.expect(requestTo(APPROVAL_URL))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withSuccess(successResponse(), MediaType.APPLICATION_JSON));

		NaverPayResponse<NaverPayApproveBody> response = client.approve("pg-payment-id");

		assertThat(response.getCode()).isEqualTo("Success");
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getDetail().getPaymentId()).isEqualTo("pg-payment-id");
	}

	@DisplayName("응답 파싱이 실패하면 INVALID_RESPONSE 예외가 발생한다")
	@Test
	void approve_whenInvalidResponse_throwException() {
		server.expect(requestTo(APPROVAL_URL))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.approve("pg-payment-id"))
			.isInstanceOfSatisfying(NaverPayException.class, ex -> {
				assertThat(ex.getErrorCode()).isEqualTo(NaverPayErrorCode.INVALID_RESPONSE);
			});
	}

	@DisplayName("인증 실패면 AUTHENTICATION 예외가 발생한다")
	@Test
	void approve_whenUnauthorized_throwException() {
		server.expect(requestTo(APPROVAL_URL))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withStatus(HttpStatus.UNAUTHORIZED));

		assertThatThrownBy(() -> client.approve("pg-payment-id"))
			.isInstanceOfSatisfying(NaverPayException.class, ex -> {
				assertThat(ex.getErrorCode()).isEqualTo(NaverPayErrorCode.AUTHENTICATION);
			});
	}

	@DisplayName("서버 오류면 SERVER_ERROR 예외가 발생한다")
	@Test
	void approve_whenServerError_throwException() {
		server.expect(requestTo(APPROVAL_URL))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

		assertThatThrownBy(() -> client.approve("pg-payment-id"))
			.isInstanceOfSatisfying(NaverPayException.class, ex -> {
				assertThat(ex.getErrorCode()).isEqualTo(NaverPayErrorCode.SERVER_ERROR);
			});
	}

	@DisplayName("클라이언트 오류면 CLIENT_ERROR 예외가 발생한다")
	@Test
	void approve_whenClientError_throwException() {
		server.expect(requestTo(APPROVAL_URL))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST));

		assertThatThrownBy(() -> client.approve("pg-payment-id"))
			.isInstanceOfSatisfying(NaverPayException.class, ex -> {
				assertThat(ex.getErrorCode()).isEqualTo(NaverPayErrorCode.CLIENT_ERROR);
			});
	}

	@DisplayName("네트워크 오류면 NETWORK 예외가 발생한다")
	@Test
	void approve_whenNetworkError_throwException() {
		RestTemplate failingRestTemplate = mock(RestTemplate.class);
		when(failingRestTemplate.postForEntity(
			eq(APPROVAL_URL),
			any(),
			eq(String.class)))
			.thenThrow(new ResourceAccessException("timeout"));

		ReflectionTestUtils.setField(client, "naverPayRestTemplate", failingRestTemplate);

		assertThatThrownBy(() -> client.approve("pg-payment-id"))
			.isInstanceOfSatisfying(NaverPayException.class, ex -> {
				assertThat(ex.getErrorCode()).isEqualTo(NaverPayErrorCode.NETWORK);
			});
	}

	@DisplayName("취소 요청이 성공하면 응답이 매핑된다")
	@Test
	void cancel_whenSuccess_mapResponse() {
		server.expect(requestTo(CANCEL_URL))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withSuccess(cancelSuccessResponse(), MediaType.APPLICATION_JSON));

		NaverPayResponse<NaverPayCancelBody> response = client.cancel(createCancelRequest());

		assertThat(response.getCode()).isEqualTo("Success");
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getPaymentId()).isEqualTo("pg-payment-id");
	}

	@DisplayName("취소 요청에서 인증 실패면 AUTHENTICATION 예외가 발생한다")
	@Test
	void cancel_whenUnauthorized_throwException() {
		server.expect(requestTo(CANCEL_URL))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withStatus(HttpStatus.UNAUTHORIZED));

		assertThatThrownBy(() -> client.cancel(createCancelRequest()))
			.isInstanceOfSatisfying(NaverPayException.class, ex -> {
				assertThat(ex.getErrorCode()).isEqualTo(NaverPayErrorCode.AUTHENTICATION);
			});
	}

	@DisplayName("취소 요청에서 서버 오류면 SERVER_ERROR 예외가 발생한다")
	@Test
	void cancel_whenServerError_throwException() {
		server.expect(requestTo(CANCEL_URL))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

		assertThatThrownBy(() -> client.cancel(createCancelRequest()))
			.isInstanceOfSatisfying(NaverPayException.class, ex -> {
				assertThat(ex.getErrorCode()).isEqualTo(NaverPayErrorCode.SERVER_ERROR);
			});
	}

	@DisplayName("취소 요청에서 클라이언트 오류면 CLIENT_ERROR 예외가 발생한다")
	@Test
	void cancel_whenClientError_throwException() {
		server.expect(requestTo(CANCEL_URL))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST));

		assertThatThrownBy(() -> client.cancel(createCancelRequest()))
			.isInstanceOfSatisfying(NaverPayException.class, ex -> {
				assertThat(ex.getErrorCode()).isEqualTo(NaverPayErrorCode.CLIENT_ERROR);
			});
	}

	@DisplayName("취소 요청에서 응답 파싱이 실패하면 INVALID_RESPONSE 예외가 발생한다")
	@Test
	void cancel_whenInvalidResponse_throwException() {
		server.expect(requestTo(CANCEL_URL))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.cancel(createCancelRequest()))
			.isInstanceOfSatisfying(NaverPayException.class, ex -> {
				assertThat(ex.getErrorCode()).isEqualTo(NaverPayErrorCode.INVALID_RESPONSE);
			});
	}

	@DisplayName("취소 요청에서 네트워크 오류면 NETWORK 예외가 발생한다")
	@Test
	void cancel_whenNetworkError_throwException() {
		RestTemplate failingRestTemplate = mock(RestTemplate.class);
		when(failingRestTemplate.postForEntity(
			eq(CANCEL_URL),
			any(),
			eq(String.class)))
			.thenThrow(new ResourceAccessException("timeout"));

		ReflectionTestUtils.setField(client, "naverPayRestTemplate", failingRestTemplate);

		assertThatThrownBy(() -> client.cancel(createCancelRequest()))
			.isInstanceOfSatisfying(NaverPayException.class, ex -> {
				assertThat(ex.getErrorCode()).isEqualTo(NaverPayErrorCode.NETWORK);
			});
	}

	@DisplayName("결제내역 조회 요청이 성공하면 응답이 매핑된다")
	@Test
	void getApprovalHistory_whenSuccess_mapResponse() {
		server.expect(requestTo(RESOLVED_PAYMENT_HISTORY_URL))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withSuccess(historySuccessResponse(), MediaType.APPLICATION_JSON));

		var response = client.getApprovalHistory("pg-payment-id");

		assertThat(response.getCode()).isEqualTo("Success");
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getList()).isNotEmpty();
		assertThat(response.getBody().getList().getFirst().getMerchantPayKey()).isEqualTo("PAY-TEST");
	}

	@DisplayName("결제내역 조회 응답 코드가 InvalidMerchant면 코드가 그대로 매핑된다")
	@Test
	void getApprovalHistory_whenInvalidMerchantCode_mapResponse() {
		server.expect(requestTo(RESOLVED_PAYMENT_HISTORY_URL))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withSuccess("""
				{
				  "code": "InvalidMerchant",
				  "message": "유효하지 않은 가맹점",
				  "body": null
				}
				""", MediaType.APPLICATION_JSON));

		var response = client.getApprovalHistory("pg-payment-id");

		assertThat(response.getCode()).isEqualTo("InvalidMerchant");
	}

	@DisplayName("결제내역 조회 응답 코드가 MaintenanceOngoing이면 코드가 그대로 매핑된다")
	@Test
	void getApprovalHistory_whenMaintenanceCode_mapResponse() {
		server.expect(requestTo(RESOLVED_PAYMENT_HISTORY_URL))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withSuccess("""
				{
				  "code": "MaintenanceOngoing",
				  "message": "서비스 점검중",
				  "body": null
				}
				""", MediaType.APPLICATION_JSON));

		var response = client.getApprovalHistory("pg-payment-id");

		assertThat(response.getCode()).isEqualTo("MaintenanceOngoing");
	}

	private static String successResponse() {
		return """
			{
			  "code": "Success",
			  "message": "성공",
			  "body": {
			    "detail": {
			      "paymentId": "pg-payment-id",
			      "merchantPayKey": "PAY-TEST",
			      "totalPayAmount": 1000
			    }
			  }
			}
			""";
	}

	private static String cancelSuccessResponse() {
		return """
			{
			  "code": "Success",
			  "message": "성공",
			  "body": {
			    "paymentId": "pg-payment-id",
			    "payHistId": "cancel-hist-id",
			    "cancelYmdt": "20260204123000",
			    "totalRestAmount": 0
			  }
			}
			""";
	}

	private static String historySuccessResponse() {
		return """
			{
			  "code": "Success",
			  "message": "성공",
			  "body": {
			    "list": [
			      {
			        "paymentId": "pg-payment-id",
			        "admissionState": "SUCCESS",
			        "admissionTypeCode": "01",
			        "totalPayAmount": 1000,
			        "merchantPayKey": "PAY-TEST"
			      }
			    ],
			    "totalCount": 1,
			    "responseCount": 1,
			    "totalPageCount": 1,
			    "currentPageNumber": 1
			  }
			}
			""";
	}

	private NaverPayCancelRequest createCancelRequest() {
		return NaverPayCancelRequest.builder()
			.paymentId("pg-payment-id")
			.cancelAmount(1000)
			.cancelReason("test-cancel")
			.cancelRequester(NaverPayCancelRequester.CANCEL_BY_ADMIN)
			.taxScopeAmount(1000)
			.taxExScopeAmount(0)
			.build();
	}
}
