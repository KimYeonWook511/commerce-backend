package com.commerce.payment.domain;

/**
 * 결제 시도의 승인 생명주기.
 *
 * <p>활성 슬롯을 쥐는 상태와 놓는 상태를 여기 한 자리에 적는다. 전이 메서드마다 흩어 적으면 하나를
 * 빠뜨렸을 때 드러나지 않는데, 슬롯이 남은 종결 행은 그 주문을 영구히 결제 불가로 만들고 반대로
 * 슬롯이 빈 성공 행은 같은 주문에 결제가 둘 성립하게 한다.
 *
 * <p>{@code SUCCEEDED}는 종착이면서도 슬롯을 계속 쥔다 — 성공한 결제가 있는 주문에 새 결제가
 * 들어오면 안 되기 때문이다.
 */
public enum PaymentStatus {

	/** 행을 만들고 승인을 아직 호출하지 않음 */
	READY(false, true),
	/** 승인을 호출하고 응답 대기 */
	IN_PROGRESS(false, true),
	/** 승인 결과를 모름 */
	UNKNOWN(false, true),
	/** 승인 완료 */
	SUCCEEDED(true, true),
	/** 승인을 불렀는데 성립하지 않음 */
	FAILED(true, false),
	/** 승인은 났는데 우리가 받아들이지 않아 되돌림 */
	REJECTED(true, false),
	/** 승인을 부르지 않은 채 끝남 */
	EXPIRED(true, false);

	private final boolean terminal;
	private final boolean holdsActiveSlot;

	PaymentStatus(boolean terminal, boolean holdsActiveSlot) {
		this.terminal = terminal;
		this.holdsActiveSlot = holdsActiveSlot;
	}

	public boolean isTerminal() {
		return terminal;
	}

	public boolean holdsActiveSlot() {
		return holdsActiveSlot;
	}
}
