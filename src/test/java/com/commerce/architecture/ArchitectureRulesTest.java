package com.commerce.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * architecture.md 의 레이어·트랜잭션 경계 규칙 중 기계적으로 검증 가능한 것을 강제한다.
 *
 * - 문서(architecture.md): "어떤 규칙이 왜 있는가"의 포인터
 * - 이 테스트: "무엇이 강제되나"의 단일 출처
 *
 * 규칙의 근거는 각 @DisplayName 에 단 ADR/문서 포인터를 참고.
 *
 * NOTE: 패키지 컨벤션 가정 — com.commerce.<domain>.{presentation,application,domain,infrastructure}
 *       application 하위: usecase / service / port / dto  (skip·retry 모두 한 곳이면 usecase의 private 메서드, 여러 곳이면 support/ helper)
 *       infrastructure 하위: persistence / pg / cache / messaging / notification
 *       실제 패키지명이 다르면 아래 매처 문자열만 조정한다.
 *
 * STRICT: 전 도메인 재배치가 완료돼 위반이 0이 됐으므로, freeze 래핑을 제거하고 엄격(strict) 모드로 전환했다.
 *       archunit_store 스냅샷과 archunit.properties 도 함께 제거됐다. 이제 새 위반이 생기면 즉시 실패한다.
 */
@DisplayName("아키텍처 규칙 (architecture.md 강제)")
class ArchitectureRulesTest {

    private static final String BASE = "com.commerce";

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE);
    }

    private static void check(ArchRule rule) {
        rule.check(productionClasses);
    }

    // ────────────────────────────────────────────────────────────
    // 1. 의존 방향 — domain 은 가장 안쪽 (헥사고날)
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("domain 은 application·infrastructure·presentation 을 참조하지 않는다")
    void domainDoesNotDependOnOuterLayers() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..application..", "..infrastructure..", "..presentation..");
        check(rule);
    }

    @Test
    @DisplayName("domain 은 Spring 트랜잭션·기술 클라이언트를 참조하지 않는다 (순수 도메인 로직)")
    void domainDoesNotDependOnSpringTech() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework.transaction..",
                        "org.springframework.kafka..",
                        "org.springframework.data.redis..")
                // 엔티티 매핑 애너테이션(@Entity/@Id/@Version/@Column 등)은 허용한다(입장 B).
                // 순수 POJO 도메인 객체 + 별도 매핑 클래스 도입 비용이 비효율적이라 판단해,
                // domain 은 "선언적 매핑 메타데이터"까지만 허용한다.
                // 단, 동작하는 JPA 런타임(EntityManager 로 직접 쿼리/flush 등)이 domain 에 들어오면 진짜 오염이므로 금지.
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("jakarta.persistence.EntityManager")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("jakarta.persistence.EntityManagerFactory");
        check(rule);
    }

    // ────────────────────────────────────────────────────────────
    // 2. 트랜잭션 경계 — @Transactional 은 service 패키지에만
    //    근거: 충돌 catch 는 tx 경계 밖에서. usecase(및 그 안의 private skip 메서드)는 tx 를 열지 않는다.
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("@Transactional 은 application.service 패키지에만 둔다")
    void transactionalOnlyInServicePackage() {
        ArchRule rule = noMethods()
                .that().areDeclaredInClassesThat()
                .resideInAPackage("..application.usecase..")
                .should().beAnnotatedWith("org.springframework.transaction.annotation.Transactional");
        check(rule);
    }

    @Test
    @DisplayName("application 계층 클래스에 class-level @Transactional 을 두지 않는다 (메서드별 tx 정책을 코드 표면에 명시 — 누락이 silent readOnly 가 아니라 tx 없음으로 드러남)")
    void noClassLevelTransactionalInApplication() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..application..")
                .should().beAnnotatedWith("org.springframework.transaction.annotation.Transactional");
        check(rule);
    }

    @Test
    @DisplayName("presentation(진입점)에는 @Transactional 을 두지 않는다")
    void noTransactionalInPresentation() {
        ArchRule ruleMethods = noMethods()
                .that().areDeclaredInClassesThat().resideInAPackage("..presentation..")
                .should().beAnnotatedWith("org.springframework.transaction.annotation.Transactional");

        ArchRule ruleClasses = noClasses()
                .that().resideInAPackage("..presentation..")
                .should().beAnnotatedWith("org.springframework.transaction.annotation.Transactional");

        check(ruleMethods);
        check(ruleClasses);
    }

    // ────────────────────────────────────────────────────────────
    // 3. 예외 노출 경계 — 예외의 추상 수준으로 긋는다
    //    구현체에 묶인 구체 예외(org.springframework.orm·org.hibernate·jakarta.persistence)는
    //    application·domain·presentation 에 노출 금지. 특정 구현에 묶이지 않은 DAO 추상 예외
    //    (org.springframework.dao)는 application 허용, domain 은 그조차 금지.
    //    번역은 안쪽이 그 예외에 따라 다르게 처리할 때만 (docs/exception-strategy.md·docs/optimistic-lock-design.md).
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("domain 은 DAO 추상 예외(org.springframework.dao)조차 참조하지 않는다 (가장 안쪽, 순수)")
    void domainReferencesNoDaoExceptions() {
        // domain 은 가장 안쪽이라 어떤 영속성 예외도 모른다. application 은 특정 구현에 묶이지 않은
        // DAO 추상 예외(org.springframework.dao.*)를 다뤄도 되지만, domain 은 그조차 참조하지 않는다.
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.dao..");
        check(rule);
    }

    @Test
    @DisplayName("구현체에 묶인 예외(org.springframework.orm·org.hibernate·jakarta.persistence)는 application·domain·presentation 에 노출되지 않는다")
    void implementationExceptionsDoNotLeakInward() {
        // 구현체에 묶인 구체 예외는 안쪽으로 새지 않는다. DAO 추상 상위
        // (org.springframework.dao.OptimisticLockingFailureException 등)를 잡으면 그 하위 구현체 타입이
        // 다형적으로 걸리므로, 안쪽 코드가 구현체 타입 이름을 부를 일이 없다.
        // (엔티티의 jakarta.persistence·org.hibernate 매핑 애너테이션은 예외 타입이 아니라 걸리지 않는다.)
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("..application..", "..domain..", "..presentation..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.orm..")
                .orShould().dependOnClassesThat().areAssignableTo("org.hibernate.HibernateException")
                .orShould().dependOnClassesThat().areAssignableTo("jakarta.persistence.PersistenceException");
        check(rule);
    }

    // ────────────────────────────────────────────────────────────
    // 4. flush 경로 — saveAndFlush 는 persistence adapter 에서만
    //    근거: 충돌을 tx 내부에서 변환하려면 flush 를 adapter 프레임으로 당겨야 함
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("saveAndFlush 호출은 infrastructure.persistence 에서만 한다")
    void saveAndFlushOnlyInPersistence() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("..infrastructure.persistence..")
                .should().callMethodWhere(DescribedPredicate.describe(
                        "이름이 saveAndFlush 인 메서드",
                        target -> target.getName().equals("saveAndFlush")));
        check(rule);
    }

    // ────────────────────────────────────────────────────────────
    // 5. 진입점 격리 — @Scheduled / @KafkaListener 는 presentation 하위에만
    //    근거: 무엇이 깨우든 진입점은 inbound adapter, application Service 에 직접 달지 않음
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("@Scheduled 는 presentation 하위에만 둔다")
    void scheduledOnlyInPresentation() {
        ArchRule rule = methods()
                .that().areAnnotatedWith("org.springframework.scheduling.annotation.Scheduled")
                .should().beDeclaredInClassesThat().resideInAPackage("..presentation..");
        check(rule);
    }

    @Test
    @DisplayName("@KafkaListener 는 presentation.consumer 하위에만 둔다")
    void kafkaListenerOnlyInConsumer() {
        ArchRule rule = methods()
                .that().areAnnotatedWith("org.springframework.kafka.annotation.KafkaListener")
                .should().beDeclaredInClassesThat().resideInAPackage("..presentation.consumer..");
        check(rule);
    }

    // ────────────────────────────────────────────────────────────
    // 6. 기술 누수 차단 — application 은 기술 타입을 직접 참조하지 않음 (port 로만)
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("application 은 KafkaTemplate·Redis 클라이언트를 직접 참조하지 않는다")
    void applicationDoesNotDependOnTechClients() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..application..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework.kafka..",
                        "org.springframework.data.redis..",
                        "redis.clients..");
        check(rule);
    }

    // ────────────────────────────────────────────────────────────
    // 7. 레이어 접근 — adapter 구현이 repository port 를 구현 (의존 방향 보존)
    //    (선택: LayeredArchitecture 로 전체 의존 방향을 한 번에 검증해도 됨)
    // ────────────────────────────────────────────────────────────

    // ────────────────────────────────────────────────────────────
    // 8. 명칭 규칙 — usecase/service 패키지 접미사 강제
    //    근거: tx 여부로 역할 분류 (usecase = 흐름 조립/tx 없음, service = tx 단위작업)
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("usecase 패키지의 클래스명은 UseCase 접미사를 가진다 (tx 없는 orchestrator 역할 명시)")
    void usecaseClassesShouldEndWithUseCase() {
        ArchRule rule = classes().that().resideInAPackage("..application.usecase..")
                .and().areTopLevelClasses()
                .should().haveSimpleNameEndingWith("UseCase");
        check(rule);
    }

    @Test
    @DisplayName("service 패키지의 클래스명은 Service 접미사를 가진다 (tx 단위작업 역할 명시)")
    void serviceClassesShouldEndWithService() {
        ArchRule rule = classes().that().resideInAPackage("..application.service..")
                .and().areTopLevelClasses()
                .should().haveSimpleNameEndingWith("Service");
        check(rule);
    }

    @Test
    @DisplayName("presentation 은 낙관 락 충돌 예외 계층을 catch 하지 않는다 (전파 → application 재시도 / 끝단 409)")
    void controllersDoNotCatchConflictExceptions() {
        // presentation 은 충돌을 직접 다루지 않고 전파한다 — 재시도는 application(usecase), 최종 409 매핑은
        // GlobalExceptionHandler(common)의 몫이다. 정확 타입이 아니라 계층 전체
        // (OptimisticLockingFailureException 및 하위 ObjectOptimisticLockingFailureException 등)를 대상으로 한다.
        // ArchUnit 은 catch 블록 자체는 직접 매칭하기 어려워, 충돌 예외 타입에 대한 의존으로 대신 검사한다.
        // OrderExpirationBatchConfig: Spring Batch fault-tolerance(.retry/.skip)는 충돌 타입을 프레임워크에
        // 선언적으로 신고하는 경계라 business catch 가 아니다. 좁게 이 한 클래스만 예외처로 둔다.
        ArchRule rule = noClasses()
                .that().resideInAPackage("..presentation..")
                .and().areNotAssignableTo("com.commerce.order.presentation.batch.OrderExpirationBatchConfig")
                .should().dependOnClassesThat()
                .areAssignableTo("org.springframework.dao.OptimisticLockingFailureException");
        check(rule);
    }
}
