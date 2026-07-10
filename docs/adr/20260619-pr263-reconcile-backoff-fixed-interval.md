# 대사 재조회 backoff 간격은 단일 고정 값으로 둔다

- Status: accepted
- Date: 2026-06-19

## Context

고정 간격만으로 두 목표(starvation 해소, PG 조회 빈도 감소)가 모두 충족된다. 스캔 윈도우 상한(6시간)이 무한 재시도를 이미 막아 한 건의 PG 조회는 `6h / 간격`으로 bound된다. 코드베이스 기조(과설계 방지·단일 값 우선·운영 config 승격 전제)에 맞춰 가장 작은 설계로 시작한다.

고려한 대안: 지수 backoff(`next_reconcile_at` + 시도 카운터) — #239의 "점증"에 충실하나 컬럼·상태·테스트가 늘어 현재 스코프에 과하다. 필요해지면 가산적으로 도입.

## Decision

같은 PR에서 도입한 `next_reconcile_at` 재조회 backoff의 간격은 단일 고정 간격(초기값 5분, `PaymentPostProcessTargetPolicy` 단일 출처)으로 한다. 시도 횟수에 따라 간격을 늘리는 지수 backoff는 도입하지 않는다.

## Consequences

- 영구 정체 transient는 고정 간격으로 escalation 상한까지 `6h/간격`회 PG를 두드린다(지수보다 많음). 그러나 이는 PG 읽기 호출일 뿐 금전 변이가 아니고 상한으로 bound되어 허용 범위다.
