package com.commerce.payment.legacy.naverpay.application.port;

import com.commerce.payment.legacy.naverpay.application.port.result.NaverPayApproveResult;
import com.commerce.payment.legacy.naverpay.application.port.result.NaverPayCancelResult;
import com.commerce.payment.legacy.naverpay.application.port.result.NaverPayHistoryResult;

public interface NaverPayGateway {

	NaverPayApproveResult approve(String pgPaymentId);

	NaverPayHistoryResult getApprovalHistory(String pgPaymentId);

	NaverPayCancelResult cancel(String pgPaymentId, int cancelAmount, String cancelReason);
}
