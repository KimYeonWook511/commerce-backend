# gateway resolver·공통 승인 진입 UseCase는 이번에 도입하지 않는다

- Status: accepted
- Date: 2026-06-18

## Context

결제 provider가 NaverPay 하나뿐이다. NaverPay 승인은 `ready→approve(redirect)`·`ALREADY_COMPLETE` 같은 특유의 상태머신을 갖고, 카카오/토스는 또 다르다(#240).

정규화 경계는 두 번째 provider의 실제 모양을 봐야 제대로 그어진다. 가상의 provider로 추상화하면 한 곳에만 맞는 틀린 추상이 나온다(YAGNI, "맥락이 달라지는 시점에 분리").

## Decision

provider별 PG 프로토콜을 추상화하는 gateway resolver, 공통 승인 진입 UseCase, PG 결과 정규화 레이어는 이번 범위에서 만들지 않는다. provider 특화 진입점은 PG 프로토콜 흐름을 담는 진입점으로 유지하고, 그 안의 provider 공통 "confirm"만 같은 PR에서 도입한 facade로 추출한다. 다만 facade를 provider 중립 위치에 둠으로써 두 번째 provider 진입점이 같은 facade를 재사용할 토대는 미리 깔아둔다.

## Consequences

- 두 번째 provider 도입 시 진입 UseCase 신설·gateway 추상화·결과 정규화가 후속 작업으로 남는다.
- 대사·보상 통지의 채널 adapter를 후속으로 분리하기로 한 기존 결정(→ PR#237)과 같은 점진적 분리 접근이다.
