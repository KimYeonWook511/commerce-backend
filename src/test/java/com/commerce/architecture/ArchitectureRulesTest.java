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
    // 3. 예외 변환 격리 — JPA/DAO 예외 타입은 persistence 밖에서 모른다
    //    근거: 기술 예외 → 도메인 예외 변환은 adapter 에서만 한다 (충돌 설계 문서 docs/optimistic-lock-design.md)
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("JPA/DAO 예외 타입은 infrastructure.persistence 밖에서 참조하지 않는다")
    void daoExceptionsConfinedToPersistence() {
        // GlobalExceptionHandler: HTTP 매핑을 위해 Spring DAO 예외를 직접 다뤄야 하는 영구 예외처.
        // OrderExpirationBatchConfig: Spring Batch fault-tolerance(.retry/.skip)는 프레임워크에 예외 타입을
        // 선언적으로 신고하는 경계라 변환 대상이 없다. GlobalExceptionHandler 와 같은 부류의 영구 예외처.
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("..infrastructure.persistence..")
                .and().areNotAssignableTo("com.commerce.common.exception.GlobalExceptionHandler")
                .and().areNotAssignableTo("com.commerce.order.presentation.batch.OrderExpirationBatchConfig")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.orm.ObjectOptimisticLockingFailureException")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.dao.OptimisticLockingFailureException")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.dao.DataIntegrityViolationException")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("jakarta.persistence.OptimisticLockException");
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
    @DisplayName("Controller 는 충돌 예외를 직접 catch 하지 않는다 (GlobalExceptionHandler 위임)")
    void controllersDoNotCatchConflictExceptions() {
        // ArchUnit 은 catch 블록 자체를 직접 매칭하기 어렵다.
        // 차선책: presentation 이 충돌/낙관락 예외 타입에 의존하지 않음을 검사.
        // OrderExpirationBatchConfig: Spring Batch fault-tolerance(.retry/.skip)는 선언적 신고라 변환 대상이 없다.
        ArchRule rule = noClasses()
                .that().resideInAPackage("..presentation..")
                .and().areNotAssignableTo("com.commerce.order.presentation.batch.OrderExpirationBatchConfig")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.orm.ObjectOptimisticLockingFailureException")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.dao.OptimisticLockingFailureException");
        check(rule);
    }
}
