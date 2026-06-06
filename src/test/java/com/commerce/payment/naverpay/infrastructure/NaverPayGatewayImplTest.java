package com.commerce.payment.naverpay.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import com.commerce.payment.naverpay.application.port.result.NaverPayApproveResult;
import com.commerce.payment.naverpay.application.port.result.NaverPayCancelResult;
import com.commerce.payment.naverpay.application.port.result.NaverPayHistoryResult;
import com.commerce.payment.naverpay.exception.NaverPayErrorCode;
import com.commerce.payment.naverpay.exception.NaverPayException;
import com.commerce.payment.naverpay.infrastructure.client.NaverPayClient;
import com.commerce.payment.naverpay.infrastructure.client.request.NaverPayCancelRequest;
import com.commerce.payment.naverpay.infrastructure.client.response.NaverPayResponse;
import com.commerce.payment.naverpay.infrastructure.client.response.body.NaverPayApproveBody;
import com.commerce.payment.naverpay.infrastructure.client.response.body.NaverPayCancelBody;
import com.commerce.payment.naverpay.infrastructure.client.response.body.NaverPayHistoryBody;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@ExtendWith(MockitoExtension.class)
class NaverPayGatewayImplTest {

	@Mock
	private NaverPayClient naverPayClient;

	@InjectMocks
	private NaverPayGatewayImpl gateway;

	private ListAppender<ILoggingEvent> listAppender;
	private Logger logger;

	@BeforeEach
	void setUp() {
		logger = (Logger) LoggerFactory.getLogger(NaverPayGatewayImpl.class);
		logger.setLevel(Level.TRACE);
		listAppender = new ListAppender<>();
		listAppender.start();
		logger.addAppender(listAppender);
	}

	@AfterEach
	void tearDown() {
		logger.detachAppender(listAppender);
		logger.setLevel(null);
	}

	@Test
	@DisplayName("approve 정상 응답 시 요청 INFO + 응답 INFO 2건이 남고 메시지가 한국어다")
	void approve_success_twoInfoLogs() {
		// Given
		NaverPayApproveBody.Detail detail = mock(NaverPayApproveBody.Detail.class);
		given(detail.getMerchantPayKey()).willReturn("merchantKey");
		given(detail.getTotalPayAmount()).willReturn(10000);

		NaverPayApproveBody body = mock(NaverPayApproveBody.class);
		given(body.getDetail()).willReturn(detail);

		@SuppressWarnings("unchecked")
		NaverPayResponse<NaverPayApproveBody> response = mock(NaverPayResponse.class);
		given(response.getCode()).willReturn("Success");
		given(response.getBody()).willReturn(body);
		given(naverPayClient.approve("PAY001")).willReturn(response);

		// When
		NaverPayApproveResult result = gateway.approve("PAY001");

		// Then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveResult.Status.SUCCESS);
		List<ILoggingEvent> logs = listAppender.list;
		assertThat(logs).hasSize(2);
		assertThat(logs.get(0).getLevel()).isEqualTo(Level.INFO);
		assertThat(logs.get(0).getFormattedMessage()).contains("네이버페이 승인 요청").contains("PAY001");
		assertThat(logs.get(1).getLevel()).isEqualTo(Level.INFO);
		assertThat(logs.get(1).getFormattedMessage()).contains("네이버페이 승인 응답").contains("PAY001");
	}

	@Test
	@DisplayName("approve 실패 코드 응답 시 요청 INFO + 실패 WARN이 남는다")
	void approve_failCode_infoAndWarn() {
		// Given
		@SuppressWarnings("unchecked")
		NaverPayResponse<NaverPayApproveBody> response = mock(NaverPayResponse.class);
		given(response.getCode()).willReturn("Fail");
		given(naverPayClient.approve("PAY002")).willReturn(response);

		// When
		NaverPayApproveResult result = gateway.approve("PAY002");

		// Then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveResult.Status.FAILED);
		List<ILoggingEvent> logs = listAppender.list;
		assertThat(logs).hasSize(3);
		assertThat(logs.get(0).getLevel()).isEqualTo(Level.INFO);
		assertThat(logs.get(0).getFormattedMessage()).contains("네이버페이 승인 요청").contains("PAY002");
		assertThat(logs.get(1).getLevel()).isEqualTo(Level.INFO);
		assertThat(logs.get(1).getFormattedMessage()).contains("네이버페이 승인 응답").contains("PAY002");
		assertThat(logs.get(2).getLevel()).isEqualTo(Level.WARN);
		assertThat(logs.get(2).getFormattedMessage()).contains("네이버페이 승인 실패").contains("PAY002");
	}

	@Test
	@DisplayName("approve 호출 시 네트워크 오류(NETWORK)는 결과 불명이므로 UNKNOWN으로 분류한다")
	void approve_networkError_returnsUnknown() {
		// Given: timeout/네트워크 단절은 PG 처리 여부가 불명하므로 FAILED가 아닌 UNKNOWN이어야 한다 (이중결제 방지)
		given(naverPayClient.approve("PAY003"))
			.willThrow(new NaverPayException(NaverPayErrorCode.NETWORK, "네트워크 오류"));

		// When
		NaverPayApproveResult result = gateway.approve("PAY003");

		// Then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveResult.Status.UNKNOWN);
		List<ILoggingEvent> logs = listAppender.list;
		assertThat(logs).hasSize(2);
		assertThat(logs.get(0).getLevel()).isEqualTo(Level.INFO);
		assertThat(logs.get(0).getFormattedMessage()).contains("네이버페이 승인 요청").contains("PAY003");
		assertThat(logs.get(1).getLevel()).isEqualTo(Level.WARN);
		assertThat(logs.get(1).getFormattedMessage()).contains("네이버페이 승인 결과 불명").contains("PAY003");
	}

	@Test
	@DisplayName("approve 호출 시 서버 오류(SERVER_ERROR)와 응답 해석 불가(INVALID_RESPONSE)도 UNKNOWN으로 분류한다")
	void approve_serverErrorOrInvalidResponse_returnsUnknown() {
		// Given
		given(naverPayClient.approve("PAY-SVR"))
			.willThrow(new NaverPayException(NaverPayErrorCode.SERVER_ERROR, "PG 서버 오류"));
		given(naverPayClient.approve("PAY-INV"))
			.willThrow(new NaverPayException(NaverPayErrorCode.INVALID_RESPONSE, "응답 해석 불가"));

		// When & Then
		assertThat(gateway.approve("PAY-SVR").getStatus()).isEqualTo(NaverPayApproveResult.Status.UNKNOWN);
		assertThat(gateway.approve("PAY-INV").getStatus()).isEqualTo(NaverPayApproveResult.Status.UNKNOWN);
	}

	@Test
	@DisplayName("approve 호출 시 인증 실패/잘못된 요청은 처리되지 않았음이 확실하므로 FAILED로 분류한다")
	void approve_clientOrAuthError_returnsFailed() {
		// Given
		given(naverPayClient.approve("PAY-AUTH"))
			.willThrow(new NaverPayException(NaverPayErrorCode.AUTHENTICATION, "인증 실패"));
		given(naverPayClient.approve("PAY-CLIENT"))
			.willThrow(new NaverPayException(NaverPayErrorCode.CLIENT_ERROR, "잘못된 요청"));

		// When & Then
		assertThat(gateway.approve("PAY-AUTH").getStatus()).isEqualTo(NaverPayApproveResult.Status.FAILED);
		assertThat(gateway.approve("PAY-CLIENT").getStatus()).isEqualTo(NaverPayApproveResult.Status.FAILED);
	}

	@Test
	@DisplayName("approve 중 NaverPayException이 아닌 예외(프로그래밍 버그)는 UNKNOWN으로 흡수하지 않고 그대로 전파한다")
	void approve_nonNaverPayException_propagates() {
		// Given: NPE 같은 프로그래밍 버그가 UNKNOWN으로 흡수되면 해당 주문이 영구 차단(brick)되므로,
		// 안전망(500)에 위임되도록 그대로 전파돼야 한다.
		given(naverPayClient.approve("PAY-BUG"))
			.willThrow(new NullPointerException("프로그래밍 버그"));

		// When & Then
		assertThatThrownBy(() -> gateway.approve("PAY-BUG"))
			.isInstanceOf(NullPointerException.class);
	}

	@Test
	@DisplayName("approve 응답이 Success인데 detail 파싱에 실패하면 PG가 승인했을 수 있으므로 UNKNOWN으로 분류한다")
	void approve_successButDetailParsingFails_returnsUnknown() {
		// Given: PG가 Success로 응답(승인 확정)했으나 detail이 null이면, FAILED로 두면 재결제가 허용돼
		// 이중결제 위험이 있다. 결과 불명(UNKNOWN)으로 보존해 재시도를 차단해야 한다.
		NaverPayApproveBody body = mock(NaverPayApproveBody.class);
		given(body.getDetail()).willReturn(null);

		@SuppressWarnings("unchecked")
		NaverPayResponse<NaverPayApproveBody> response = mock(NaverPayResponse.class);
		given(response.getCode()).willReturn("Success");
		given(response.getBody()).willReturn(body);
		given(naverPayClient.approve("PAY-DETAIL")).willReturn(response);

		// When
		NaverPayApproveResult result = gateway.approve("PAY-DETAIL");

		// Then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveResult.Status.UNKNOWN);
	}

	@Test
	@DisplayName("cancel 정상 응답 시 요청 INFO + 응답 INFO 2건이 남는다")
	void cancel_success_twoInfoLogs() {
		// Given
		@SuppressWarnings("unchecked")
		NaverPayResponse<NaverPayCancelBody> response = mock(NaverPayResponse.class);
		given(response.getCode()).willReturn("Success");
		given(naverPayClient.cancel(any(NaverPayCancelRequest.class))).willReturn(response);

		// When
		NaverPayCancelResult result = gateway.cancel("PAY004", 10000, "고객 요청");

		// Then
		assertThat(result.getStatus()).isEqualTo(NaverPayCancelResult.Status.SUCCESS);
		List<ILoggingEvent> logs = listAppender.list;
		assertThat(logs).hasSize(2);
		assertThat(logs.get(0).getLevel()).isEqualTo(Level.INFO);
		assertThat(logs.get(0).getFormattedMessage()).contains("네이버페이 취소 요청").contains("PAY004");
		assertThat(logs.get(1).getLevel()).isEqualTo(Level.INFO);
		assertThat(logs.get(1).getFormattedMessage()).contains("네이버페이 취소 응답").contains("PAY004");
	}

	@Test
	@DisplayName("getApprovalHistory 정상 응답 시 요청 INFO + 응답 INFO 2건이 남는다")
	void getApprovalHistory_success_twoInfoLogs() {
		// Given
		NaverPayHistoryBody.History history = mock(NaverPayHistoryBody.History.class);
		given(history.isCompletedApproval()).willReturn(true);
		given(history.getMerchantPayKey()).willReturn("merchantKey");
		given(history.getTotalPayAmount()).willReturn(10000);

		NaverPayHistoryBody body = mock(NaverPayHistoryBody.class);
		given(body.getList()).willReturn(List.of(history));

		@SuppressWarnings("unchecked")
		NaverPayResponse<NaverPayHistoryBody> response = mock(NaverPayResponse.class);
		given(response.getCode()).willReturn("Success");
		given(response.getBody()).willReturn(body);
		given(naverPayClient.getAllHistory("PAY005")).willReturn(response);

		// When
		NaverPayHistoryResult result = gateway.getApprovalHistory("PAY005");

		// Then
		assertThat(result.getStatus()).isEqualTo(NaverPayHistoryResult.Status.APPROVED);
		List<ILoggingEvent> logs = listAppender.list;
		assertThat(logs).hasSize(2);
		assertThat(logs.get(0).getLevel()).isEqualTo(Level.INFO);
		assertThat(logs.get(0).getFormattedMessage()).contains("네이버페이 이력조회 요청").contains("PAY005");
		assertThat(logs.get(1).getLevel()).isEqualTo(Level.INFO);
		assertThat(logs.get(1).getFormattedMessage()).contains("네이버페이 이력조회 응답").contains("PAY005");
	}
}
