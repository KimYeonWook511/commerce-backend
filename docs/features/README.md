# 기능별 문서 운영 가이드

이 문서는 기능 단위 작업을 `docs/features/<feature-name>/` 아래에서 어떻게 관리할지 정의한다.

## 기본 원칙

- 루트 `docs/` 문서는 프로젝트 전체의 기준 문서다.
- 기능 구현 중 상세 기획과 설계는 `docs/features/<feature-name>/`에서 관리한다.
- 기능 구현이 완료되면 필요한 내용을 루트 `docs/` 문서에 반영한다.
- 기능 폴더 문서는 삭제하지 않고 유지하며, 이후 변경의 근거 문서로 사용한다.

## 디렉터리 구조

```text
docs/
└── features/
    ├── README.md                    # 기능 문서 운영 가이드
    ├── _templates/                  # 기능 문서 기본 템플릿
    │   ├── prd.md
    │   ├── architecture.md
    │   ├── adr.md
    │   ├── api-spec.md
    │   └── db-schema.md
    └── <feature-name>/              # 개별 기능 작업 루트
        ├── prd.md                   # 기능 요구사항
        ├── architecture.md          # 기능 구조 설계
        ├── adr.md                   # 기능별 설계 결정
        ├── api-spec.md              # 기능 API 계약
        ├── db-schema.md             # 기능 DB 변경 사항
        └── phases/
            ├── index.json           # 기능 내부 phase 목록
            └── <phase-name>/
                ├── index.json       # 현재 phase의 step 상태
                ├── step0.md         # 첫 번째 실행 step
                └── step1.md         # 두 번째 실행 step
```

위 디렉터리 구조를 기준으로 보면:

- `docs/features/<feature-name>/`는 해당 기능의 작업 루트다.
- `prd.md`, `architecture.md`, `adr.md`, `api-spec.md`, `db-schema.md`는 기능 기획과 설계 문서다.
- `phases/index.json`은 해당 기능 내부의 phase 목록을 관리한다.
- `phases/<phase-name>/index.json`은 해당 phase의 step 상태를 관리한다.
- `phases/<phase-name>/step0.md`, `step1.md`는 실제 실행 단위 문서다.

## 기본 생성 문서

새 기능 폴더를 만들 때는 아래 문서를 기본 생성한다.

- `prd.md`
- `architecture.md`
- `adr.md`
- `api-spec.md`
- `db-schema.md`

각 문서는 `docs/features/_templates/` 아래 템플릿을 복사해 시작한다.

## 기능별 `phases`

- 기능별 step 실행 상태는 `docs/features/<feature-name>/phases/` 아래에서 관리한다.
- `docs/features/<feature-name>/phases/index.json`은 해당 기능 내부의 phase 목록만 관리한다.
- 각 phase 상세 상태는 `docs/features/<feature-name>/phases/<phase-name>/index.json`에 둔다.
- phase 이름은 `<순번>-<slug>` 형식을 따른다.

예:

```text
docs/features/<feature-name>/phases/index.json
docs/features/<feature-name>/phases/<phase-name>/index.json
docs/features/<feature-name>/phases/<phase-name>/step0.md
```

## 문서 우선순위

기능 작업 시 agent는 아래 순서로 문서를 읽는다.

1. `AGENTS.md`
2. `docs/features/<feature-name>/` 아래 기능 문서
3. 해당 기능의 `phases` 문서와 step 문서
4. 공통 구조나 다른 도메인 정보가 더 필요할 때만 루트 `docs/` 기준 문서

## 완료 후 반영 규칙

기능 구현이 끝나면 아래를 함께 수행한다.

1. 기능 폴더 문서 기준으로 구현과 검증을 마친다.
2. 루트 `docs/architecture.md`, `docs/api-spec.md`, `docs/ADR.md`, `docs/db-schema.md` 등 필요한 기준 문서에 반영한다.
3. 기능 폴더 문서는 유지한다.
