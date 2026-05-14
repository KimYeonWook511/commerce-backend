package com.commerce.payment.naverpay.application.port;

import com.commerce.payment.naverpay.application.port.result.NaverPayApproveResult;
import com.commerce.payment.naverpay.application.port.result.NaverPayCancelResult;
import com.commerce.payment.naverpay.application.port.result.NaverPayHistoryResult;

public interface NaverPayGateway {

	NaverPayApproveResult approve(String paymentId);

	NaverPayHistoryResult getApprovalHistory(String paymentId);

	NaverPayCancelResult cancel(String paymentId, int cancelAmount, String cancelReason);
}
