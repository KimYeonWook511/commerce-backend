package com.commerce.payment.naverpay.client;

import java.util.UUID;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import com.commerce.payment.naverpay.config.NaverPayProperties;
import com.commerce.payment.naverpay.client.response.body.NaverPayApproveBody;
import com.commerce.payment.naverpay.client.response.NaverPayResponse;
import com.commerce.payment.naverpay.exception.NaverPayErrorCode;
import com.commerce.payment.naverpay.exception.NaverPayException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class NaverPayClient {

	private final RestTemplate restTemplate = new RestTemplate();
	private final NaverPayProperties properties;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public NaverPayResponse<NaverPayApproveBody> approve(String paymentId) {
		HttpHeaders headers = new HttpHeaders();
		headers.set("X-Naver-Client-Id", properties.getClientId());
		headers.set("X-Naver-Client-Secret", properties.getClientSecret());
		headers.set("X-NaverPay-Chain-Id", properties.getChainId());
		headers.set("X-NaverPay-Idempotency-Key", UUID.randomUUID().toString()); // 해당 멱등키를 우리 서버에서도 써야할까?
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

		// 동일 키에 대해 여러 value 허용하기
		MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
		body.add("paymentId", paymentId);

		HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
		try {
			String response = restTemplate.postForObject(properties.getApprovalUrl(), request, String.class);
			if (response == null) {
				throw new NaverPayException(NaverPayErrorCode.INVALID_RESPONSE, "네이버페이 응답이 비어있습니다");
			}
			return objectMapper.readValue(response, new TypeReference<>() {});
		} catch (ResourceAccessException ex) {
			// 네트워크/타임아웃 오류
			throw new NaverPayException(NaverPayErrorCode.NETWORK, "네이버페이 요청 중 네트워크 오류가 발생했습니다", ex);
		} catch (RestClientResponseException ex) {
			// 4xx, 5xx 실패
			HttpStatusCode statusCode = ex.getStatusCode();
			if (statusCode.isSameCodeAs(HttpStatus.UNAUTHORIZED) || statusCode.isSameCodeAs(HttpStatus.FORBIDDEN)) {
				throw new NaverPayException(NaverPayErrorCode.AUTHENTICATION, "네이버페이 인증에 실패했습니다", ex);
			}
			if (statusCode.is5xxServerError()) {
				throw new NaverPayException(NaverPayErrorCode.SERVER_ERROR, "네이버페이 서버 오류가 발생했습니다", ex);
			}
			throw new NaverPayException(NaverPayErrorCode.CLIENT_ERROR, "네이버페이 요청이 거절되었습니다", ex);
		} catch (JsonProcessingException ex) {
			// 파싱 오류
			throw new NaverPayException(NaverPayErrorCode.INVALID_RESPONSE, "네이버페이 응답 파싱에 실패했습니다", ex);
		} catch (Exception ex) {
			throw new NaverPayException(NaverPayErrorCode.INVALID_RESPONSE, "네이버페이 응답 처리에 실패했습니다", ex);
		}
	}
}
