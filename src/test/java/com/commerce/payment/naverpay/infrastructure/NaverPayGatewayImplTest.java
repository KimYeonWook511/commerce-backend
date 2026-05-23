package com.commerce.payment.naverpay.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
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
	@DisplayName("approve 호출 예외 시 요청 INFO + 호출 실패 WARN이 남는다")
	void approve_naverPayException_infoAndWarn() {
		// Given
		given(naverPayClient.approve("PAY003"))
			.willThrow(new NaverPayException(NaverPayErrorCode.NETWORK, "네트워크 오류"));

		// When
		NaverPayApproveResult result = gateway.approve("PAY003");

		// Then
		assertThat(result.getStatus()).isEqualTo(NaverPayApproveResult.Status.FAILED);
		List<ILoggingEvent> logs = listAppender.list;
		assertThat(logs).hasSize(2);
		assertThat(logs.get(0).getLevel()).isEqualTo(Level.INFO);
		assertThat(logs.get(0).getFormattedMessage()).contains("네이버페이 승인 요청").contains("PAY003");
		assertThat(logs.get(1).getLevel()).isEqualTo(Level.WARN);
		assertThat(logs.get(1).getFormattedMessage()).contains("네이버페이 승인 호출 실패").contains("PAY003");
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
