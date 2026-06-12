# Step 6: move-naverpay-pg

## 읽어야 할 파일

- `/docs/tasks/structure-migration/prd.md`
- `/docs/tasks/structure-migration/architecture.md`
- `/docs/package-structure-guide.md` (3장 infrastructure — pg 경계, provider 묶음 주의)

## 작업

naverpay PG 연동 구현을 `infrastructure/pg/`로 **순수 이동**한다. naverpay `infrastructure` 하위 전체(top-level 구현 + `client/`·`code/` 서브트리)를 `infrastructure/pg/` 아래로 옮긴다. `git mv` + package/import 갱신(main·test). 내용 불변.

이동 규칙: `com.commerce.payment.naverpay.infrastructure.*` → `com.commerce.payment.naverpay.infrastructure.pg.*` (서브패키지 구조 보존).

이동 대상:

- top-level: `NaverPayClientConfig`, `NaverPayGatewayImpl`, `NaverPayProperties`
- `client/` 서브트리: `NaverPayClient`, `client/request/*`(`NaverPayApprovalType`, `NaverPayCancelRequest`, `NaverPayCancelRequester`, `NaverPayHistoryRequest`), `client/response/*`(`NaverPayResponse`), `client/response/body/*`(`NaverPayApproveBody`, `NaverPayCancelBody`, `NaverPayHistoryBody`) → `pg/client/...`
- `code/` 서브트리: `NaverPayApproveCode`, `NaverPayCancelCode`, `NaverPayHistoryCode` → `pg/code/`

주의:
- `NaverPayGatewayImpl`은 `NaverPayGateway` 포트(`naverpay/application/port/`) 구현이다. application 쪽 import는 포트를 보므로 바뀌지 않지만, 빈 등록/참조하는 곳과 test의 구현체 import는 갱신한다.
- naverpay의 `application`/`presentation`/`domain`은 이 step에서 건드리지 않는다. infrastructure만 pg로 묶는다.

## Acceptance Criteria

```bash
./gradlew test
./gradlew integrationTest
```

- 통합 테스트로 PG 게이트웨이 빈 와이어링과 Spring context 부팅이 정상인지 확인한다.

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - naverpay infrastructure 전체가 `infrastructure/pg/` 아래로(서브트리 구조 보존) 옮겨졌는가?
   - 구현체를 참조하는 test·config import가 갱신됐는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 클래스 이름·PG 연동 로직을 바꾸지 마라. 이유: 순수 이동 PR.
- naverpay의 application/presentation/domain을 건드리지 마라. 이유: 이 step은 infrastructure→pg 이동만 한다.
- `client/`·`code/` 서브패키지 구조를 평탄화하거나 재구성하지 마라. 이유: 순수 이동이므로 내부 구조를 보존한다.
- 기존 테스트를 깨뜨리지 마라.
