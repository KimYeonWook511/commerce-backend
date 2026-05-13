package com.commerce.payment.naverpay.infrastructure.client;

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

import com.commerce.payment.naverpay.infrastructure.NaverPayProperties;
import com.commerce.payment.naverpay.infrastructure.client.request.NaverPayCancelRequest;
import com.commerce.payment.naverpay.infrastructure.client.request.NaverPayApprovalType;
import com.commerce.payment.naverpay.infrastructure.client.response.body.NaverPayApproveBody;
import com.commerce.payment.naverpay.infrastructure.client.response.body.NaverPayCancelBody;
import com.commerce.payment.naverpay.infrastructure.client.request.NaverPayHistoryRequest;
import com.commerce.payment.naverpay.infrastructure.client.response.NaverPayResponse;
import com.commerce.payment.naverpay.infrastructure.client.response.body.NaverPayHistoryBody;
import com.commerce.payment.naverpay.exception.NaverPayErrorCode;
import com.commerce.payment.naverpay.exception.NaverPayException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class NaverPayClient {

	private final NaverPayProperties properties;
	private final RestTemplate naverPayRestTemplate;
	private final ObjectMapper objectMapper;

	public NaverPayResponse<NaverPayApproveBody> approve(String paymentId) {
		HttpHeaders headers = createHeaders();
		headers.set("X-NaverPay-Idempotency-Key", UUID.randomUUID().toString());
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

		MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
		body.add("paymentId", paymentId);

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

	public NaverPayResponse<NaverPayHistoryBody> getApprovalHistory(String paymentId) {
		HttpHeaders headers = createHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);

		NaverPayHistoryRequest body = NaverPayHistoryRequest.builder()
			.approvalType(NaverPayApprovalType.APPROVAL)
			.build();

		String url = properties.getPaymentHistoryUrl().replace("{paymentId}", paymentId);
		return post(url, headers, body, new TypeReference<>() {});
	}

	public NaverPayResponse<NaverPayHistoryBody> getCancelHistory(String paymentId) {
		HttpHeaders headers = createHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);

		NaverPayHistoryRequest body = NaverPayHistoryRequest.builder()
			.approvalType(NaverPayApprovalType.CANCEL)
			.build();

		String url = properties.getPaymentHistoryUrl().replace("{paymentId}", paymentId);
		return post(url, headers, body, new TypeReference<>() {});
	}

	public NaverPayResponse<NaverPayHistoryBody> getAllHistory(String paymentId) {
		HttpHeaders headers = createHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);

		NaverPayHistoryRequest body = NaverPayHistoryRequest.builder()
			.approvalType(NaverPayApprovalType.ALL)
			.build();

		String url = properties.getPaymentHistoryUrl().replace("{paymentId}", paymentId);
		return post(url, headers, body, new TypeReference<>() {});
	}

	private <B, T> T post(String url, HttpHeaders headers, B body, TypeReference<T> typeReference) {
		HttpEntity<B> request = new HttpEntity<>(body, headers);
		try {
			ResponseEntity<String> response = naverPayRestTemplate.postForEntity(url, request,
				String.class);
			if (response.getBody() == null) {
				throw new NaverPayException(NaverPayErrorCode.INVALID_RESPONSE, "네이버페이 응답이 비어있습니다");
			}
			return objectMapper.readValue(response.getBody(), typeReference);
		} catch (ResourceAccessException ex) {
			throw new NaverPayException(NaverPayErrorCode.NETWORK, "네이버페이 요청 중 네트워크 오류가 발생했습니다", ex);
		} catch (RestClientResponseException ex) {
			throw mapHttpException(ex);
		} catch (JsonProcessingException ex) {
			throw new NaverPayException(NaverPayErrorCode.INVALID_RESPONSE, "네이버페이 응답 파싱에 실패했습니다", ex);
		} catch (Exception ex) {
			throw new NaverPayException(NaverPayErrorCode.INVALID_RESPONSE, "네이버페이 응답 처리에 실패했습니다", ex);
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
