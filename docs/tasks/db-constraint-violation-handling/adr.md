# 태스크 ADR

> [!NOTE]
> 본 문서의 정책은 후속 태스크 `docs/tasks/unique-find-first-policy/` 에서 재정의되었다. 현재 정책은 루트 `docs/architecture.md` 의 예외 처리 섹션과 `docs/tasks/unique-find-first-policy/adr.md` 를 참조한다.

## 결정 제목

DB 무결성 예외 catch 범위 정책 — Application 계층에서 `DuplicateKeyException`만 좁게 catch한다.

## 배경

Spring의 `DataIntegrityViolationException`은 여러 DB 무결성 위반을 묶는 부모 타입이다.
현재 5곳 모두 이 부모 타입을 catch하여 unique 위반 의도의 fallback을 수행하지만,
NOT NULL / FK / CHECK 위반(= 코드 버그)도 동일 fallback을 탈 위험이 있다.

Adapter에서 도메인 예외로 변환할지, Application에서 직접 처리할지 결정이 필요했다.

## 결정 내용

**Application 계층에서 `DuplicateKeyException`만 좁게 catch한다. Adapter 변환 레이어는 도입하지 않는다.**

## 근거

1. **처리 동작이 5곳마다 다르다**: 멱등 재조회, 도메인 예외 변환, silent skip 등 공통 변환 레이어가 의미 없다.
2. **도메인 지식이 Adapter로 새어든다**: `DuplicateKeyException` → `DUPLICATE_EMAIL` 같은 매핑은 도메인 지식인데, 이를 Adapter가 알아야 하면 레이어 책임이 어긋난다.
3. **Spring이 이미 충분한 추상을 제공한다**: `DuplicateKeyException`은 vendor 중립적 unique 위반 추상이다. 한 번 더 감싸는 건 YAGNI.
4. **5곳 중 4곳은 unique가 하나뿐이거나 의미가 통일**되어 catch되면 그 의미로 확정된다. 분기 코드 불필요.

## 고려한 대안

| 대안 | 이유 채택 안 함 |
|---|---|
| Adapter에서 도메인 예외로 변환 (DDD 정통) | 도메인 매핑 지식이 인프라로 유출. 5곳 처리가 달라 공통 변환 불가. |
| `DataConstraintViolationException(ErrorCode)` 공통 예외 도입 | 추가 클래스 + 매핑 테이블. Application에서 다시 분기 필요. 이중 추상화. |
| `ex.getMessage()` 파싱으로 제약명 추출 | DB/버전 종속적 안티패턴. |

## 결과

- 코드 변경량 최소: catch 타입 한 줄 + import 교체만.
- NOT NULL/FK/CHECK 위반은 GlobalExceptionHandler 안전망(500)으로 가시화.
- `DuplicateKeyException`이 DB/드라이버에 따라 다르게 매핑될 수 있으므로 Testcontainers 회귀 방어 테스트를 추가한다.
