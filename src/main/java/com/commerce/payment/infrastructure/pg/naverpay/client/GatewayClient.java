package com.commerce.payment.infrastructure.pg.naverpay.client;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import com.commerce.payment.application.port.dto.PgCallSource;
import com.commerce.payment.domain.PgErrorType;
import com.commerce.payment.infrastructure.pg.naverpay.Properties;
import com.commerce.payment.infrastructure.pg.naverpay.client.request.ApprovalType;
import com.commerce.payment.infrastructure.pg.naverpay.client.request.CancelRequester;
import com.commerce.payment.infrastructure.pg.naverpay.client.request.HistoryRequest;

import lombok.RequiredArgsConstructor;

/**
 * 결제사를 실제로 부르는 자리. 요청을 만들어 보내고 돌아온 것을 그대로 나른다.
 *
 * <p>예외를 밖으로 던지지 않는다. 응답을 못 받은 경우도 전송 단계 판정을 담아 돌려주므로, 부르는 쪽이
 * 예외 처리와 결과 해석을 나눠 생각하지 않아도 된다.
 */
@Component
@RequiredArgsConstructor
public class GatewayClient {

	private static final String PAYMENT_ID_PLACEHOLDER = "{paymentId}";
	private static final String IDEMPOTENCY_KEY_HEADER = "X-NaverPay-Idempotency-Key";
	private static final int HISTORY_ROWS_PER_PAGE = 100;

	private final Properties properties;
	private final RestTemplate naverPayRestTemplate;
	private final RestTemplate naverPayBatchRestTemplate;

	public GatewayExchange approve(String pgPaymentId, String pgIdempotencyKey) {
		HttpHeaders headers = createHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		headers.set(IDEMPOTENCY_KEY_HEADER, pgIdempotencyKey);

		MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
		body.add("paymentId", pgPaymentId);

		return post(properties.getApprovalUrl(), headers, body, PgCallSource.MEMBER_REQUEST);
	}

	/**
	 * 취소를 요청한다. 요청자·사유·과세·면세가 전부 결제사 필수 필드라 하나라도 빠지면 요청이 거절된다.
	 *
	 * <p>과세와 면세를 나눠 싣고 그 합이 취소 금액과 같아야 한다. 이번 범위는 전액을 과세로 다룬다 —
	 * 결제 시작이 같은 규칙으로 보내고 있고, 주문·상품 어디에도 둘을 가르는 정보가 없다.
	 *
	 * <p>잔액 대조 값은 싣지 않는다. 우리 구조는 결과를 모르는 환불이 떠 있는 것이 정상이라, 그 값을
	 * 실으면 시차가 오류로 취급되어 그 뒤 환불이 전부 거절된다.
	 */
	public GatewayExchange refund(
		String pgPaymentId,
		String refundAttemptKey,
		String pgIdempotencyKey,
		int amount,
		String reason,
		CancelRequester requester,
		PgCallSource source
	) {
		HttpHeaders headers = createHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		headers.set(IDEMPOTENCY_KEY_HEADER, pgIdempotencyKey);

		MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
		body.add("paymentId", pgPaymentId);
		// 우리 환불 시도 키를 결제사가 거래 고유 번호로 받는 자리에 싣는다. 이력에서 우리 시도를
		// 집어내는 것이 이 값이라, 안 실으면 이중환불 탐지가 통째로 무력해진다.
		body.add("merchantPayTransactionKey", refundAttemptKey);
		body.add("cancelAmount", String.valueOf(amount));
		body.add("cancelReason", reason);
		body.add("cancelRequester", requester.getCode());
		body.add("taxScopeAmount", String.valueOf(amount));
		body.add("taxExScopeAmount", "0");

		return post(properties.getCancelUrl(), headers, body, source);
	}

	public GatewayExchange readHistory(String pgPaymentId, ApprovalType approvalType, int pageNumber) {
		HttpHeaders headers = createHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);

		HistoryRequest body = new HistoryRequest(approvalType, pageNumber, HISTORY_ROWS_PER_PAGE);
		String url = properties.getHistoryUrl().replace(PAYMENT_ID_PLACEHOLDER, pgPaymentId);

		return post(url, headers, body, PgCallSource.MEMBER_REQUEST);
	}

	/** 읽기 제한 시간이 진입점마다 다르므로 클라이언트를 그 자리에 맞춰 고른다 */
	private RestTemplate restTemplateFor(PgCallSource source) {
		return source == PgCallSource.BATCH ? naverPayBatchRestTemplate : naverPayRestTemplate;
	}

	private <B> GatewayExchange post(String url, HttpHeaders headers, B body, PgCallSource source) {
		try {
			ResponseEntity<String> response = restTemplateFor(source)
				.postForEntity(url, new HttpEntity<>(body, headers), String.class);
			return GatewayExchange.responded(response.getStatusCode().value(), response.getBody());
		} catch (ResourceAccessException ex) {
			return GatewayExchange.notResponded(classifyTransportFailure(ex));
		} catch (RuntimeException ex) {
			// 요청을 보낸 뒤에 터진 것인지 그 전인지 코드로 가릴 수 없다. 안 나갔다고 단정하면 이미
			// 처리된 요청을 다시 보내게 되므로 응답을 못 읽은 쪽으로 둔다.
			return GatewayExchange.notResponded(PgErrorType.TIMEOUT);
		}
	}

	/**
	 * 연결이 안 된 것과 보냈는데 응답을 못 읽은 것을 가른다. 타임아웃은 연결 단계와 읽기 단계가 같은
	 * 예외 타입으로 오는데, 연결 단계로 잘못 보면 이미 나간 요청을 안 나간 것으로 다루게 되므로 확실히
	 * 연결에서 끝난 것만 연결 실패로 둔다.
	 */
	private PgErrorType classifyTransportFailure(ResourceAccessException ex) {
		Throwable cause = ex.getCause();
		if (cause instanceof UnknownHostException
			|| cause instanceof ConnectException
			|| cause instanceof NoRouteToHostException) {
			return PgErrorType.CONNECT;
		}
		return PgErrorType.TIMEOUT;
	}

	private HttpHeaders createHeaders() {
		HttpHeaders headers = new HttpHeaders();
		headers.set("X-Naver-Client-Id", properties.getClientId());
		headers.set("X-Naver-Client-Secret", properties.getClientSecret());
		headers.set("X-NaverPay-Chain-Id", properties.getChainId());
		return headers;
	}
}
