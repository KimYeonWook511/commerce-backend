# API Spec — 대사 스캔 KEEP_WAITING backoff

이 작업은 외부 API 계약(엔드포인트·요청·응답·실패코드)을 **변경하지 않는다**.

대사는 `@Scheduled` 내부 스케줄러(`PaymentReconciliationScheduler`)로만 구동되며 HTTP 엔드포인트가
없다. backoff는 그 내부 재조회 cadence만 바꾸므로 사용자 관측 계약에 영향이 없다.
