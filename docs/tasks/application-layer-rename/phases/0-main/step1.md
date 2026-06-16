# Step 1: rename-member-domain

## 읽어야 할 파일

먼저 아래 파일들을 읽고 작업 맥락을 파악하라:

- `docs/tasks/application-layer-rename/prd.md`
- `docs/tasks/application-layer-rename/adr.md`
- `docs/adr.md` — ADR-054 항목 확인 (application 계층 명명 컨벤션)
- `src/main/java/com/commerce/member/application/service/MemberQueryService.java`
- `src/main/java/com/commerce/member/application/service/MemberRegistrationService.java`

## 작업

member 도메인 Service 클래스 2개를 ADR-054 `{행위}{대상}Service` 컨벤션으로 리네임한다.
동작 변경 없이 파일명·클래스명·주입 변수명·테스트명만 바꾼다.

### 리네임 목록

| 현재 | 변경 후 |
|---|---|
| `MemberQueryService` | `FindMemberService` |
| `MemberRegistrationService` | `RegisterMemberService` |

### 절차

1. 각 대상 클래스를 사용하는 모든 파일을 확인한다.

   ```bash
   grep -rl "MemberQueryService" src/
   grep -rl "MemberRegistrationService" src/
   ```

2. `MemberQueryService.java` → `FindMemberService.java`로 파일을 새로 생성하고 클래스명을 변경한다. 기존 파일은 삭제한다.

3. `MemberRegistrationService.java` → `RegisterMemberService.java`로 동일하게 처리한다.

4. 위에서 확인한 모든 참조 파일에서 아래를 업데이트한다:
   - `import` 경로 (클래스명 변경)
   - 타입 선언 (`MemberQueryService` → `FindMemberService`)
   - 필드/파라미터 변수명 (camelCase 기준: `memberQueryService` → `findMemberService`, `memberRegistrationService` → `registerMemberService`)

5. 테스트 파일에서 아래를 추가로 업데이트한다:
   - 테스트 클래스명 (예: `MemberQueryServiceTest` → `FindMemberServiceTest`)
   - 테스트 메서드명 및 `@DisplayName`의 클래스명 언급 부분

### 금지사항

- 클래스 내부 메서드 시그니처·로직을 변경하지 마라. 이유: 동작 불변 원칙. 이름만 바뀐다.
- 기존 테스트를 삭제하지 마라. 이유: 이름 변경 외에 테스트 검증 로직은 그대로 보존해야 한다.

## Acceptance Criteria

```bash
./gradlew test
```

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다:
   - `MemberQueryService`, `MemberRegistrationService` 문자열이 `src/` 하위에 남아 있지 않은가.
     ```bash
     grep -r "MemberQueryService\|MemberRegistrationService" src/
     ```
   - ADR-054 `{행위}{대상}Service` 어순을 따르는가.
