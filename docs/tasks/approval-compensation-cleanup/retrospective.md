# 회고록: approval-compensation-cleanup

## 1. 작업 요약

`NaverPayApprovalService.completeVerifiedApproval`(PG SUCCESS + 키·금액 검증 통과 후 호출)의 보상·예외 처리를 예외 전략(ADR-011)·완료 우선으로 정리했다. #225, PR #224 코드 리뷰에서 식별된 후속 작업이다.

- **완료 우선(ADR-032)**: 정상 승인 후 transient 기록 실패를 환불·FAILED로 박제하던 `compensateUnexpected`를 제거했다. unmapped 예외는 보상 없이 전파(500)하고 approve를 `REQUESTED`로 남겨 배치 reconcile에 위임한다. 명시적 비정상(`MERCHANT_KEY_MISMATCH`·`AMOUNT_MISMATCH`)은 *틀린 결제*라 환불을 유지한다.
- **이중결제 adapter 매핑(ADR-033)**: application의 raw `catch(DataIntegrityViolationException)`(ADR-011 위반)을 제거하고, `PaymentRepositoryAdapter.saveApproved`가 `uk_payment_approved_order_key` 위반을 `PaymentException(PAYMENT_DUPLICATE)`로 번역하도록 전환했다. 보상은 fail-first 단일 경로(`compensateDuplicatePayment`)로 통일하고 cancel-first(`compensateDuplicateApproval`)를 제거했다.

2 step(duplicate-detection-adapter-mapping, transient-failure-keep-requested)으로 구현했고, PR 리뷰(Gemini + 로컬 multi-agent)에서 `isApprovedOrderKeyViolation`의 제약명 식별 로직을 크게 다듬었다.

---

## 2. 설계 결정

자세한 본문은 [task ADR](./adr.md)(staging L1~L2) 및 루트 ADR-032~033 참조.

| ADR | 핵심 결정 |
|---|---|
| ADR-032 (L1) | 정상 승인 후 transient 기록 실패는 환불하지 않고 `REQUESTED` 유지 → reconcile self-heal. `compensateUnexpected` 제거. 명시적 비정상만 환불 유지. |
| ADR-033 (L2) | 이중결제 탐지를 adapter 도메인 예외 매핑으로 전환(ADR-011 carve-out). `saveAndFlush` 조기 flush가 위반을 adapter 호출 안에서 확정(load-bearing). 보상 fail-first 단일화. |

---

## 3. 핵심 발견과 교훈

### 테스트가 가정을 뒤집었다 — `getConstraintName()`은 이 프로젝트에서 dead 경로였다

초기 구현은 cause 체인에서 Hibernate `ConstraintViolationException.getConstraintName()`을 1차로 보고, `SQLException` 메시지 매칭을 2차 폴백으로 두었다. PR 리뷰 중 "폴백은 도달하지 않는 보험이니 제거하고 `getConstraintName()`만 남기자"고 판단해 폴백을 제거했더니, **MySQL 통합 테스트가 깨졌다.**

실패 예외가 결정적 증거였다 — `DuplicateKeyException: ... for key 'tbl_payment.uk_payment_approved_order_key'`, stacktrace에 `SQLErrorCodeSQLExceptionTranslator`. `JpaConfig`가 이 translator를 등록하기 때문에 unique 위반이 `DuplicateKeyException`(cause=JDBC `SQLException`)으로 변환되고, **cause 체인에 Hibernate `ConstraintViolationException`이 아예 없다.** 즉 `getConstraintName()` 분기는 처음부터 한 번도 타지 않는 dead 경로였고, 실제 매핑은 `SQLException` 메시지 폴백이 담당하고 있었다.

교훈: "이 폴백은 안 탈 것"이라는 추론을 코드만 보고 확신하지 말 것. 통합 테스트가 실제 런타임의 예외 변환 경로를 드러냈다. 돈 관련 경로에서는 특히, 제거하기 전에 그 경로가 정말 죽었는지 테스트로 확인해야 한다.

### `isApprovedOrderKeyViolation` short-circuit — 세 번 만에 수렴

리뷰(Gemini high + 로컬)가 짚은 핵심 버그는 판정 로직이 첫 매치에서 `false`를 **단정**한다는 것이었다. 수렴 과정:

1. 1차 시도: `getConstraintName()==null`이면 즉시 `false` 반환 → 같은 cause 체인의 `SQLException` 폴백에 도달 못 함.
2. 2차 시도: `name==null`만 폴백을 열었으나, **`name`이 non-null이지만 다른 값**일 때 여전히 `false`를 단정 → `getConstraintName()`이 부정확한 환경에서 미탐.
3. 최종: 어떤 분기도 `false`를 조기 단정하지 않고, "일치할 때만 `true`, 끝까지 못 찾으면 `false`"인 OR 구조로 정리. 이후 위 테스트 실패로 `getConstraintName` 경로 자체가 dead임이 드러나, `SQLException` 메시지 단어 경계 매칭(`\b...\b`, 대소문자 무시)으로 단일화했다.

단어 경계 매칭은 `contains`의 `uk_payment_approved_order_key_v2` 같은 prefix 공유 오탐과 대소문자 문제를 함께 막는다 — 돈 관련 식별이라 저확률 오탐도 안전장치로 처리했다.

### 발견한 근본 문제를 현재 작업에 끼워넣지 않았다 — #227 분리

translator를 제거하면 `getConstraintName()` 경로가 되살아나 식별이 깔끔해진다. 하지만 그 빈은 운영 로그에서 unique 위반을 `DuplicateKeyException` 타입으로 구분하기 위한 전역 설정이고, 제거는 전역 예외 분류·로깅을 바꾸는 결정이다. 이중결제 버그를 고치는 #225에 곁다리로 끼우면 묻혀버린다.

판단 기준: "그걸 안 고치면 현재 작업이 *틀리거나 못 끝나나?*" → 아니오(현 설정에서 `SQLException` 매칭으로 올바르게 완결, 나중에 되돌림 없이 위에 얹기 가능). 그래서 #225는 현 제약 안에서 마무리하고, translator 재검토는 근거를 담아 **#227로 분리**했다.

### adapter 도메인 예외 매핑은 ADR-011 carve-out이다

`compensateUnexpected`·`compensateDuplicateApproval` 제거는 보상 로직 중복을 오히려 줄였다. application이 raw DAO 예외에 의존하던 부채(ADR-011 위반)를 해소하고, 인프라 예외 번역을 adapter 책임으로 되돌렸다. 단 `saveAndFlush`의 조기 flush가 위반을 adapter 호출 안에서 확정하는 load-bearing 의존성이라, 이 호출을 일반 `save`로 바꾸거나 flush를 미루면 매핑이 깨진다(주석·ADR-033에 명시).

---

## 4. 후속 과제

- **#227**: 폐기된 `SQLErrorCodeSQLExceptionTranslator` 빈 재검토. 제거 시 `getConstraintName()` 경로가 되살아나 `isApprovedOrderKeyViolation`을 메시지 파싱에서 구조적 API로 단순화 가능. 전역 예외 분류·로깅 영향과 `exception-strategy.md`/ADR 갱신을 함께 따져 결정.
- **reconcile 구현(#221/#208)**: ADR-032의 "완료 우선"은 `REQUESTED` 잔여를 회수하는 배치 reconcile에 의존한다. 그 구현 전까지는 코드 레벨 self-heal 안전망이 없다.
