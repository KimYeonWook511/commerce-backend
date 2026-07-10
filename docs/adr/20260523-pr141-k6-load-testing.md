# 부하 테스트 도구로 k6 + InfluxDB + Grafana를 채택한다

- Status: accepted
- Date: 2026-05-23

## Context

주요 API의 성능을 정량적으로 측정한 데이터가 부재했고, 부하 시나리오의 정량 검증 수단이 필요했다. 운영 환경 모니터링·CI 통합은 별도 트랙으로 분리한다.

k6는 JavaScript로 시나리오를 표현해 가독성이 높고 `thresholds`로 SLO를 정량 검증할 수 있다. InfluxDB(1.8)는 k6 native output과 호환성이 검증돼 있으며(별도 xk6 빌드 불필요), Grafana 공식 k6 대시보드 템플릿(#2587)을 그대로 활용할 수 있어 시각화 도입 비용이 낮다. 대안 도구(JMeter, Gatling)는 GUI/XML 설정 부담 또는 Scala 학습 비용이 더 크다.

## Decision

부하 테스트는 k6를 사용하고, 메트릭은 InfluxDB(1.8)에 저장해 Grafana로 시각화한다. 로컬 환경에서만 실행한다.

## Consequences

부하 테스트 결과는 로컬 환경 사양에 의존하므로 절대 수치보다는 개선 전후의 상대 비교가 주된 활용 방식이다. CI 자동 실행·운영 환경 측정은 본 결정 범위 밖이며 후속 과제로 둔다.
