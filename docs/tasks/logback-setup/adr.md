# 태스크 ADR

## 결정 제목

- 본 태스크는 별도 ADR 항목을 추가하지 않는다.

## 배경

- 이번 태스크의 핵심 결정(콘솔 텍스트 + 파일 JSON 분리, 환경별 ROOT 레벨, 마스킹 정책, p6spy 단일화)은 이미 머지된 `docs/logging-conventions.md`(이슈 #127)가 결정 기록 역할을 한다.
- logback-spring.xml은 그 정책의 implementation이며, ADR로 따로 기록할 새로운 설계 결정이 없다.

## 결정 내용

- 루트 `docs/adr.md`에 신규 ADR을 추가하지 않는다.
- 본 태스크 내부에서 내린 인프라 차원의 작은 결정들은 본 태스크의 `prd.md`/`architecture.md`에 기록한다.

## 근거

- ADR은 "되돌리기 어려운 설계 결정"을 다루는 문서다. 컨벤션 문서가 이미 그 역할을 하는 상황에서 ADR을 중복 추가하면 단일 진실의 원천 원칙에 어긋난다.
- 본 태스크 진행 중 내린 결정(logstash-encoder 버전, rolling 수치, 마스킹 구현 방식 등)은 구현 디테일에 가깝고, 향후 변경 비용도 낮다.

## 결과

- 결정 기록은 `docs/logging-conventions.md`(원칙), 본 태스크 폴더(구현 디테일), 회고록(`retrospective.md`, 진행 중 트레이드오프)으로 3중 보관된다.
- adr.md는 깨끗하게 유지된다.

## 본 태스크 내부에서 내린 인프라 결정 요약

| 항목 | 결정 | 근거 |
|------|------|------|
| logstash-logback-encoder | 7.4 | Spring Boot 3.5.9 + Logback 1.5.x 호환 안정 시리즈 |
| 로그 파일 경로 | `./logs/app.log` 고정 (env override 없음) | 단순성 우선. 운영 환경 경로 매핑은 컨테이너 볼륨 책임 |
| 콘솔 색상 | local만 ANSI | prod는 docker stdout 캡처 시 ANSI 코드가 노이즈 |
| 파일 JSON 마스킹 방식 | 커스텀 `MaskingMessageJsonProvider` | LogstashEncoder의 `<pattern>` provider로 직접 JSON 조립 시 따옴표/제어문자 escape 위험. 30 LOC 클래스가 더 안전 |
| AsyncAppender `neverBlock=true` | 채택 | 일반 application 로그 throughput 우선. ERROR 무손실은 향후 별도 검토 |
| p6spy + `org.hibernate.SQL` 중복 처리 | p6spy만 활성, hibernate.SQL은 모든 환경 OFF | 동일 SQL이 두 logger로 중복 출력되는 것을 차단 |
| prod p6spy 레벨 | OFF | 운영 디스크·노이즈·성능 회피 |
