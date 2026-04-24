# 브랜치 컨벤션

이 문서는 현재 Repo에서 사용하는 브랜치 이름 규칙을 정의한다.

## 허용 브랜치 타입

- `feature/<name>`: 새로운 기능 개발
- `fix/<name>`: 버그 수정
- `docs/<name>`: 문서 작업
- `chore/<name>`: 설정, 도구, 운영성 작업
- `refactor/<name>`: 동작 변화 없는 구조 개선
- `test/<name>`: 테스트 추가 또는 수정

## 기본 원칙

- 브랜치 이름은 `<type>/<name>` 형식을 따른다.
- `/` 뒤의 `name`은 kebab-case를 사용한다.
- 하나의 브랜치는 하나의 작업 의도를 표현한다.
- 기능 내부 `phase`는 작업 분해 단위로만 사용하고, 브랜치 이름에는 넣지 않는다.

## 예시

- `feature/skill-test`
- `fix/auth-token-expiry`
- `docs/dev-start-guide`
- `chore/codex-harness-engineering`
- `refactor/payment-service`
- `test/auth-controller`
