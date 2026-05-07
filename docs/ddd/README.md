# DDD 문서 운영 가이드

이 디렉터리는 기능 단위 PRD가 아니라 도메인 구조 개선, DDD 마이그레이션 전략, 도메인별 회고 문서를 관리한다.

## 문서 기준

- `ddd-migration-plan.md`: DDD 마이그레이션의 공통 방향과 패키지 구조 기준
- `<domain>-ddd-migration-retrospective.md`: 도메인별 DDD 마이그레이션 회고와 후속 작업
- `auth-ddd-migration-retrospective.md`: 인증 bounded context와 security 웹 adapter 분리 기준

## 작성 원칙

- 여러 도메인에 공통으로 적용되는 구조 기준은 `docs/ddd/`에 둔다.
- 마이그레이션 회고에는 실제 변경 내용, 남은 legacy 제거 범위, 다음 도메인 작업에 적용할 기준을 남긴다.
