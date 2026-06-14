package com.commerce.payment.naverpay.infrastructure.pg.client;

import java.util.UUID;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import com.commerce.payment.naverpay.infrastructure.pg.NaverPayProperties;
import com.commerce.payment.naverpay.infrastructure.pg.client.request.NaverPayCancelRequest;
import com.commerce.payment.naverpay.infrastructure.pg.client.request.NaverPayApprovalType;
import com.commerce.payment.naverpay.infrastructure.pg.client.response.body.NaverPayApproveBody;
import com.commerce.payment.naverpay.infrastructure.pg.client.response.body.NaverPayCancelBody;
import com.commerce.payment.naverpay.infrastructure.pg.client.request.NaverPayHistoryRequest;
import com.commerce.payment.naverpay.infrastructure.pg.client.response.NaverPayResponse;
import com.commerce.payment.naverpay.infrastructure.pg.client.response.body.NaverPayHistoryBody;
import com.commerce.payment.naverpay.domain.exception.NaverPayErrorCode;
import com.commerce.payment.naverpay.domain.exception.NaverPayException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class NaverPayClient {

	private final NaverPayProperties properties;
	private final RestTemplate naverPayRestTemplate;
	private final ObjectMapper objectMapper;

	public NaverPayResponse<NaverPayApproveBody> approve(String pgPaymentId) {
		HttpHeaders headers = createHeaders();
		headers.set("X-NaverPay-Idempotency-Key", UUID.randomUUID().toString());
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

		MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
		body.add("paymentId", pgPaymentId);

		return post(properties.getApprovalUrl(), headers, body, new TypeReference<>() {});
	}

	public NaverPayResponse<NaverPayCancelBody> cancel(NaverPayCancelRequest cancelRequest) {
		HttpHeaders headers = createHeaders();
		headers.set("X-NaverPay-Idempotency-Key", UUID.randomUUID().toString());
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

		MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
		body.add("paymentId", cancelRequest.getPaymentId());
		body.add("cancelAmount", String.valueOf(cancelRequest.getCancelAmount()));
		body.add("cancelReason", cancelRequest.getCancelReason());
		body.add("cancelRequester", cancelRequest.getCancelRequester().getCode());
		body.add("taxScopeAmount", String.valueOf(cancelRequest.getTaxScopeAmount()));
		body.add("taxExScopeAmount", String.valueOf(cancelRequest.getTaxExScopeAmount()));

		return post(properties.getCancelUrl(), headers, body, new TypeReference<>() {});
	}

	public NaverPayResponse<NaverPayHistoryBody> getApprovalHistory(String pgPaymentId) {
		HttpHeaders headers = createHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);

		NaverPayHistoryRequest body = NaverPayHistoryRequest.builder()
			.approvalType(NaverPayApprovalType.APPROVAL)
			.build();

		String url = properties.getPaymentHistoryUrl().replace("{paymentId}", pgPaymentId);
		return post(url, headers, body, new TypeReference<>() {});
	}

	public NaverPayResponse<NaverPayHistoryBody> getCancelHistory(String pgPaymentId) {
		HttpHeaders headers = createHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);

		NaverPayHistoryRequest body = NaverPayHistoryRequest.builder()
			.approvalType(NaverPayApprovalType.CANCEL)
			.build();

		String url = properties.getPaymentHistoryUrl().replace("{paymentId}", pgPaymentId);
		return post(url, headers, body, new TypeReference<>() {});
	}

	public NaverPayResponse<NaverPayHistoryBody> getAllHistory(String pgPaymentId) {
		HttpHeaders headers = createHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);

		NaverPayHistoryRequest body = NaverPayHistoryRequest.builder()
			.approvalType(NaverPayApprovalType.ALL)
			.build();

		String url = properties.getPaymentHistoryUrl().replace("{paymentId}", pgPaymentId);
		return post(url, headers, body, new TypeReference<>() {});
	}

	private <B, T> T post(String url, HttpHeaders headers, B body, TypeReference<T> typeReference) {
		// 전송 전: 요청 빌드. 여기서 발생하는 예외(프로그래밍 버그 등)는 PG 에 아무것도 보내지 않은
		// 상태이므로 잡지 않고 그대로 전파해 안전망(500)에 위임한다 (이중결제 위험 없음).
		HttpEntity<B> request = new HttpEntity<>(body, headers);

		ResponseEntity<String> response;
		try {
			response = naverPayRestTemplate.postForEntity(url, request, String.class);
		} catch (ResourceAccessException ex) {
			throw new NaverPayException(NaverPayErrorCode.NETWORK, "네이버페이 요청 중 네트워크 오류가 발생했습니다", ex);
		} catch (RestClientResponseException ex) {
			throw mapHttpException(ex);
		} catch (Exception ex) {
			// 전송 단계의 그 외 예외. postForEntity 진입 후에는 요청 전송 여부를 코드로 구분할 수 없어
			// 불명으로 보고 INVALID_RESPONSE 로 보존한다 (상위에서 UNKNOWN 분류 → 이중결제 방어).
			throw new NaverPayException(NaverPayErrorCode.INVALID_RESPONSE, "네이버페이 요청 처리에 실패했습니다", ex);
		}

		// 전송 완료(PG 가 이미 처리했을 수 있는 상태). 아래 응답 해석 단계의 모든 예외(프로그래밍 버그 포함)는
		// INVALID_RESPONSE 로 보존해 상위에서 UNKNOWN 으로 분류되게 한다. 전파해 500 으로 두면 UNKNOWN 흔적이
		// 남지 않아 재결제가 허용되고, PG 가 이미 승인했다면 이중결제가 발생하기 때문이다.
		if (response.getBody() == null) {
			throw new NaverPayException(NaverPayErrorCode.INVALID_RESPONSE, "네이버페이 응답이 비어있습니다");
		}
		try {
			return objectMapper.readValue(response.getBody(), typeReference);
		} catch (Exception ex) {
			throw new NaverPayException(NaverPayErrorCode.INVALID_RESPONSE, "네이버페이 응답 해석에 실패했습니다", ex);
		}
	}

	private NaverPayException mapHttpException(RestClientResponseException ex) {
		HttpStatusCode statusCode = ex.getStatusCode();
		if (statusCode.isSameCodeAs(HttpStatus.UNAUTHORIZED) || statusCode.isSameCodeAs(HttpStatus.FORBIDDEN)) {
			return new NaverPayException(NaverPayErrorCode.AUTHENTICATION, "네이버페이 인증에 실패했습니다", ex);
		}
		if (statusCode.is5xxServerError()) {
			return new NaverPayException(NaverPayErrorCode.SERVER_ERROR, "네이버페이 서버 오류가 발생했습니다", ex);
		}
		return new NaverPayException(NaverPayErrorCode.CLIENT_ERROR, "네이버페이 요청이 거절되었습니다", ex);
	}

	private HttpHeaders createHeaders() {
		HttpHeaders headers = new HttpHeaders();
		headers.set("X-Naver-Client-Id", properties.getClientId());
		headers.set("X-Naver-Client-Secret", properties.getClientSecret());
		headers.set("X-NaverPay-Chain-Id", properties.getChainId());
		return headers;
	}
}
