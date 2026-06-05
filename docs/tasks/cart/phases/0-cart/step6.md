# Step 6: write-retrospective

## 읽어야 할 파일

먼저 아래 파일들을 읽고 이전 step의 산출물과 결정을 파악하라:

- `/docs/tasks/cart/prd.md`
- `/docs/tasks/cart/architecture.md`
- `/docs/tasks/cart/adr.md`
- `/docs/tasks/cart/api-spec.md`
- `/docs/tasks/cart/db-schema.md`
- `/docs/tasks/cart/phases/0-cart/index.json` (각 step의 summary)
- `/docs/tasks/cart/phases/0-cart/step1.md` ~ `step5.md`
- `/docs/tasks/product-management/retrospective.md` (회고록 형식 참고)
- `/docs/tasks/order-idempotency/retrospective.md` (다른 회고록 형식 참고)

## 작업

본 phase의 회고록 `docs/tasks/cart/retrospective.md`를 작성한다.

### 회고록 구성 요소

1. **태스크 개요**
   - 무엇을 했는가 (cart 도메인 신설, API 4종, 주문-cart 연동)
   - 적용한 결정 7개 요약 (adr.md 결정 1~7)
   - ADR-020 명문화 사실
2. **단계별 작업 요약**
   - Step 1: cart 도메인/repository
   - Step 2: 담기/조회 API
   - Step 3: 수정/삭제 API
   - Step 4: 주문-cart 연동
   - Step 5: 루트 docs 동기화
3. **설계 결정의 회고**
   - CartItem-only 단일 entity aggregate 선택의 배경과 이후 확장 시 고려사항
   - ID 참조 정책(ADR-020) 도입의 의의와 기존 도메인 마이그레이션 후속 트랙
   - 주문-cart 동일 트랜잭션 결정의 트레이드오프
   - "Buy Now" 호환을 위해 cart 존재 검증을 제외한 결정
4. **잘된 점 / 어려웠던 점 / 후속 과제**
   - 후속 과제 후보
     - cart 보관 기한 자동 삭제(예: 90일) phase 도입 검토
     - cart 전체 비우기 API(`DELETE /cart`) 필요성 검토
     - 기존 Order/Stock/StockHistory의 ManyToOne → ID 참조 마이그레이션 (ADR-020 후속)
     - 가격 변동 알림 정책 (현재는 조회 시점 재조회만)
     - Flyway 도입 후 `tbl_cart_item` 마이그레이션 스크립트 작성
5. **시각화/측정 결과**(선택)
   - 단위/슬라이스/통합 테스트 수
   - 신규 API 응답 시간 측정 결과(가능한 경우)

### 작성 원칙

- 회고록은 역사 기록이다. **사후 소급 수정하지 않는다.**
- 진실하고 구체적으로 작성한다. 추상적 자평 대신 사실과 결정의 이유, 의외의 발견, 잔여 부채를 기록한다.
- 문서 길이는 자연스러운 길이로. 억지로 늘리지 않는다.

## 수정 가능 경로

- `docs/tasks/cart/retrospective.md` (신규)
- `docs/tasks/cart/**` (필요 시 task 문서 갱신은 회고록 외에 가능)

## Acceptance Criteria

```bash
test -f docs/tasks/cart/retrospective.md
```

(파일 존재 확인. 별도 테스트 없음.)

## 검증 절차

1. 위 Acceptance Criteria 커맨드를 실행한다.
2. 아래를 확인한다.
   - 회고록이 단계별 결과, 결정, 후속 과제를 포함하는가?
   - ADR 결정 7개와 ADR-020 명문화를 명시하는가?
3. 결과에 따라 step 상태를 갱신한다.

## 금지사항

- 이미 작성된 다른 task의 `retrospective.md`를 수정하지 마라. 이유: 회고록은 사후 소급 수정 금지(역사 기록).
- 회고록에서 task 문서나 ADR을 수정하지 마라. 본 step은 회고록 작성 한정.
- 기존 테스트를 깨뜨리지 마라.
