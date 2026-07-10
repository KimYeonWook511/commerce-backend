# 부하 테스트

이커머스 백엔드 주요 API에 대한 k6 기반 부하 테스트 환경 및 시나리오. Epic [#137](https://github.com/KimYeonWook511/commerce-backend/issues/137) 및 부하 테스트 도구 선택 결정(→ PR#141) 참조.

## 개요

- **도구**: k6 (부하 발생), InfluxDB 1.8 (메트릭 저장), Grafana 11.x (시각화)
- **환경**: 로컬 (운영 환경 측정·CI 통합은 별도 트랙)
- **목적**: 주요 API의 baseline 측정, 병목 분석, before/after 비교를 통한 개선 검증

## 사전 준비

### 1. k6 설치

```bash
brew install k6
```

설치 확인: `k6 version` (v0.50 이상 권장)

### 2. 백엔드 로컬 실행

```bash
# 인프라 실행
docker compose -f docker-compose.local.yml up -d

# 백엔드 실행
./gradlew bootRun
```

기본 포트: `http://localhost:8080`

### 3. 상품 데이터 등록

`GET /products`는 DB의 상품 데이터를 반환한다. 데이터가 비어있으면 baseline 측정이 무의미하므로 사전에 일정 개수의 상품을 등록한다 (관리자 API 또는 SQL 직접 삽입).

## 실행 방법

### 1. 부하 테스트 인프라 기동

```bash
docker compose -f load-test/docker-compose.yml up -d
```

- InfluxDB: `localhost:18086`
- Grafana: `http://localhost:13000` (anonymous viewer)

### 2. k6 시나리오 실행

```bash
k6 run --out influxdb=http://localhost:18086/k6 load-test/scenarios/product-list.js
```

다른 `BASE_URL`로 측정하려면:

```bash
BASE_URL=http://host.docker.internal:8080 k6 run --out influxdb=... load-test/scenarios/product-list.js
```

### 3. Grafana에서 결과 확인

`http://localhost:13000` 접속 → 공식 k6 대시보드 자동 로드. 실행 중 또는 실행 후 시계열 그래프로 응답시간·TPS·VU 추이 확인.

### 4. 인프라 종료

```bash
docker compose -f load-test/docker-compose.yml down
```

볼륨까지 삭제하려면 `down -v` (다음 측정 시 Grafana·InfluxDB 상태 초기화).

## 디렉토리 구조

```
load-test/
├── README.md                              # 이 문서
├── docker-compose.yml                     # InfluxDB + Grafana
├── grafana/
│   ├── provisioning/
│   │   ├── datasources/influxdb.yml       # InfluxDB datasource 자동 등록
│   │   └── dashboards/dashboards.yml      # dashboard provider 설정
│   └── dashboards/
│       └── k6-load-testing-results.json   # Grafana 공식 #2587 대시보드
├── scenarios/                             # k6 시나리오 스크립트
│   └── product-list.js
└── results/                               # 측정 결과 기록 (YYYY-MM-DD-<scenario>.md)
```

## 시나리오 목록

| 시나리오 | 파일 | 목적 | 가설 SLO |
|---|---|---|---|
| 상품 목록 조회 baseline | `scenarios/product-list.js` | `GET /products` 단순 부하 측정 | p95 < 200ms, error < 1% |

## 결과 기록 형식

측정 결과는 `results/YYYY-MM-DD-<scenario>.md`로 작성한다. 포함 항목:

- 측정 환경 (k6 버전, 백엔드·DB 사양, 상품 데이터 개수)
- 설정 (vus, duration, thresholds)
- 측정 결과 (p50, p95, p99, TPS, 에러율, 총 요청 수)
- threshold 통과 여부
- 관찰 사항 (응답시간 분포 특징, 병목 후보)
- (선택) Grafana 스크린샷 경로

## 시나리오 작성 규칙

- **하나의 스크립트 = 하나의 가설**: 한 시나리오가 한 가지 질문에 답하도록 설계
- **`group`으로 단계 묶기**: 다단계 플로우는 `group`으로 묶어 단계별 응답시간 분리
- **think time(`sleep`)**: 현실적 트래픽 패턴을 위해 요청 간 sleep 포함
- **`thresholds` 명시**: SLO를 `thresholds`로 표현. 첫 측정 시에는 가설 SLO로 시작하고 baseline 결과에 따라 조정
- **`BASE_URL` 환경 변수**: 환경별로 override 가능하도록 `__ENV.BASE_URL` 사용
- **HTTP status check**: `check`로 응답 검증

## 환경 한계

- 본 환경의 측정 결과는 **로컬 머신 사양에 의존**한다. 절대 수치보다 **개선 전/후 상대 비교**에 활용한다.
- Grafana는 anonymous viewer가 활성화된 **로컬 전용** 설정이다. 운영 환경에 그대로 사용하지 않는다.
- 운영 환경 측정·모니터링 인프라·CI 자동 실행은 본 트랙의 범위 밖이다.

## 트러블슈팅

### Grafana 기동 시 권한 에러

bind mount(`./grafana-data-local`)는 컨테이너 Grafana UID(472)와 호스트 UID가 달라 첫 기동 시 권한 에러가 발생할 수 있다. 발생 시:

```bash
sudo chown -R 472:472 load-test/grafana-data-local
```

또는 `docker-compose.yml`의 bind mount를 named volume으로 전환한다.

### k6 InfluxDB output 경고

k6 native InfluxDB v1 output은 현재 유효하다. 향후 deprecation 시 `xk6-output-influxdb` extension으로 전환한다.

### BASE_URL 도달 불가

- 호스트에서 k6 실행: `localhost:8080` 기본값 사용
- Docker 컨테이너에서 k6 실행: `host.docker.internal:8080` (Mac/Windows) 또는 호스트 IP (Linux)

## 참고

- Epic: [#137](https://github.com/KimYeonWook511/commerce-backend/issues/137)
- 도구 선택 결정: 부하 테스트 도구로 k6 + InfluxDB + Grafana 채택 (→ PR#141)
- k6 문서: <https://grafana.com/docs/k6/latest/>
- Grafana 공식 k6 대시보드: <https://grafana.com/grafana/dashboards/2587-k6-load-testing-results/>
