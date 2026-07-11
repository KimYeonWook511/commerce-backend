# AGENTS.md

## Review guidelines

Codex가 이 저장소의 PR을 리뷰할 때 따르는 지침이다. 리뷰 코멘트는 참고용이며 머지를 차단하지 않는다.

전제: Java 21 / Spring Boot 3.5 / JPA / MySQL / Flyway, 루트 `com.commerce`(`presentation` → `application` → `domain` ← `infrastructure`). 리뷰는 심각도 높은 위험에 집중한다. 각 항목은 리뷰 전에 반드시 해당 참조 문서 본문을 읽고 그 기준으로 대조하라. 문서와 이 파일이 어긋나면 문서가 옳다.

- **코멘트 언어**: 리뷰 코멘트 본문은 한국어로 작성하며, 지적은 근거(`docs/*` 규칙)와 함께 남긴다.
- **비밀 값**: 자격 증명·API 키 등 비밀 값을 리뷰 코멘트에 남기지 않는다.
- **LGTM 명시**: 심각한(장애·데이터 정합성·보안 직결) 문제가 없으면 요약 코멘트 첫 줄에 "LGTM"을 명시하고, 확인한 범위를 한 문장으로 요약한다.

### 참조 문서 (단일 출처)

- **민감정보 노출·로깅** — `docs/logging-conventions.md`
- **레이어 경계·의존 방향** — `docs/package-structure-conventions.md`
- **도메인 모델링** — `docs/package-structure-conventions.md` (정적 팩토리로만 생성·public setter 금지·상태 변경은 도메인 메서드·cross-aggregate는 ID 참조)
- **트랜잭션 위치·역할 접미사** — `docs/package-structure-conventions.md`
- **예외 처리·find-first** — `docs/exception-strategy.md`
- **낙관 락(@Version) 충돌 처리** — `docs/optimistic-lock-design.md`
- **영속성·마이그레이션** — `docs/persistence-conventions.md`
- **테스트 코드** — `docs/test-code-conventions.md`
