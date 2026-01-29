package com.commerce.payment.naverpay.client;

import java.util.UUID;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import com.commerce.payment.naverpay.config.NaverPayProperties;
import com.commerce.payment.naverpay.client.response.NaverPayApproveResponse;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class NaverPayClient {

	private final RestTemplate restTemplate = new RestTemplate();
	private final NaverPayProperties properties;

	public NaverPayApproveResponse approve(String paymentId) {
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
		return restTemplate.postForObject(properties.getApprovalUrl(), request, NaverPayApproveResponse.class);
	}
}
