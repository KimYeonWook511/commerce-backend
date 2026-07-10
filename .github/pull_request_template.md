<!--
PR 제목은 `<type>: <변경 요약>` 형식으로 작성합니다.
변경 요약은 명사형으로 작성하고, 마침표로 끝내지 않습니다.

예:
- feat: 상품 조회 API 추가
- fix: 인증 토큰 만료 처리 수정
- refactor: 결제 승인 서비스 책임 분리
- test: 인증 컨트롤러 예외 응답 테스트 추가
- docs: 상품 조회 API 문서 보강
- chore: PR 템플릿 추가

허용 타입:
feat, fix, refactor, test, docs, chore

타입 선택 기준:
- PR의 주된 변경 목적을 기준으로 선택합니다.
- 기능 구현이 주 목적이면 docs/ 수정이 함께 있어도 feat를 사용합니다.
- 버그 수정이 주 목적이면 fix를 사용합니다.
- 코드 리팩토링(기능 변경 없는 구조 개선)은 refactor를 사용합니다.
- 테스트 코드 추가 및 수정은 test를 사용합니다.
- 설명 문서만 수정하면 docs를 사용합니다.
- 설정, 자동화, 하네스, 운영성 변경이 주 목적이면 chore를 사용합니다.
-->

## 요약

-

## 변경 사항

-

## 관련 이슈

<!--
이 PR이 해결하는 기존 이슈를 명시합니다.
- 완전 해결: `Closes #N` (머지 시 자동 close)
- 부분 해결: `Refs #N` (수동 close 필요)
- 여러 개일 경우 각 줄에 별도로 적습니다.

예:
- Closes #114
- Closes #115
- Refs #99

없으면 "없음".
-->

-

## 후속 작업

<!--
본 PR 머지 전까지 처리·체크되어야 하는 항목만 적습니다.
단순히 관련 있는 다른 이슈나 본 PR과 무관한 작업은 적지 않습니다.
모든 항목이 체크되어야 머지 가능합니다.

각 항목은 별도 issue로 등록하고 번호를 채운 뒤 체크합니다.

예:
- [x] failApproveAndCancelApprovedPayment 분리 → #119
- [x] mark 메서드 네이밍 정리 → #120

없으면 "없음".
-->

-

## 문서/하네스 변경

<!--
변경 종류로 동기화 대상을 "대조"합니다 (판단하지 말 것). CLAUDE.md "코드 변경 후 루트 문서 동기화" 표와 동일합니다.
- API 계약(엔드포인트·요청·응답·실패코드) 변경 → docs/api-spec.md 갱신
- DB 스키마(테이블·컬럼·인덱스·제약) 변경 → docs/db-schema.md 갱신 (실제 DDL은 Flyway V스크립트)
- 구조(모듈·레이어·책임·서비스 신설/이동) 변경 → docs/architecture.md 갱신
- 설계 결정(정책·트레이드오프) → docs/adr/에 새 ADR 파일 추가 (기존 ADR 수정 없이, supersede 시 옛 ADR Status만 갱신)
- 내부 구현만(이름 정리·로직 리팩터) → 동기화 불필요 → "해당 없음"
-->

- [ ] 해당 없음 (내부 구현만 변경)
- [ ] Task 문서(`docs/tasks/**`)를 함께 수정했습니다.
- [ ] 루트 기준 문서(`docs/prd.md`, `docs/api-spec.md`, `docs/architecture.md`, `docs/db-schema.md`)를 변경 종류에 맞게 갱신했습니다.
- [ ] `docs/adr/`에 새 ADR 파일을 추가했습니다 (기존 ADR 수정 없이, supersede 시 옛 ADR Status만 갱신).
- [ ] Claude Code 하네스, hook, skill 문서를 함께 수정했습니다.

## 테스트

- [ ] `./gradlew test` (단위/슬라이스 — `docker`, `sandbox`, `concurrency`, `batch`, `learning` 태그 제외)
- [ ] `./gradlew integrationTest` (Docker/Testcontainers 통합)
- [ ] `./gradlew batchTest` (Spring Batch 통합)
- [ ] 격리 task (`concurrencyTest`, `sandboxTest`) 를 별도 실행했습니다.
  - 실행 커맨드:
- [ ] 실행하지 않았습니다.
  - 사유:

## 참고

<!--
ADR, 외부 문서, 설계 결정 배경 등 리뷰어가 알면 좋은 추가 정보를 적습니다.
관련 이슈는 위 `## 관련 이슈` 섹션, 후속 작업은 `## 후속 작업` 섹션에 적습니다.
-->

-
