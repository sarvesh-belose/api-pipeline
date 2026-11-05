package com.crisil.apiorch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import java.sql.DatabaseMetaData;
import java.time.Instant;
import java.time.LocalDateTime;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * The README §13 architecture rules (US-1.1 AC11). {@link ArchUnitRulesTest} applies them to production code;
 * {@link ArchitectureRuleFixturesTest} proves each one fires on a deliberately violating fixture.
 */
public final class ArchitectureRules {

    private ArchitectureRules() {
    }

    /** Jackson 2 is the only line (ADR-003 F7). */
    public static final ArchRule NO_JACKSON_3 = noClasses()
            .should().dependOnClassesThat().resideInAPackage("tools.jackson..")
            .as("product code never imports Jackson 3 (tools.jackson)");

    /** Time comes from the injected Clock (README §13). */
    public static final ArchRule NO_WALL_CLOCK = noClasses()
            .that().resideOutsideOfPackages("com.crisil.apiorch.config..", "com.crisil.apiorch.support.h2..")
            .should().callMethod(Instant.class, "now")
            .orShould().callMethod(LocalDateTime.class, "now")
            .orShould().callMethod(System.class, "currentTimeMillis")
            .as("time comes from the injected Clock");

    /** The H2 shim is loaded by H2 only (ADR-003 rule 3). */
    public static final ArchRule NOTHING_IMPORTS_THE_H2_SHIM = noClasses()
            .that().resideOutsideOfPackage("com.crisil.apiorch.support.h2..")
            .should().dependOnClassesThat().resideInAPackage("com.crisil.apiorch.support.h2..")
            .as("nothing imports the H2 shim package");

    /** Engine packages stay pure so the dry run can reuse them (README §13). */
    public static final ArchRule ENGINE_PACKAGES_ARE_PURE = noClasses()
            .that().resideInAnyPackage("..engine..", "..expression..", "..definition..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.web.client..", "java.net.http..", "org.springframework.jdbc..",
                    "org.springframework.data.repository..")
            .as("engine, expression and definition use no HTTP client, JdbcClient or repository");

    private static final DescribedPredicate<JavaMethodCall> HTTP_CALL = new DescribedPredicate<>("an HTTP client") {
        @Override
        public boolean test(JavaMethodCall call) {
            JavaClass owner = call.getTargetOwner();
            return owner.getPackageName().startsWith("org.springframework.web.client")
                    || owner.getPackageName().startsWith("java.net.http");
        }
    };

    /** No row lock is ever held across a network call (README §5.1, §13). */
    public static final ArchRule NO_HTTP_CALL_IN_TRANSACTION = noMethods()
            .that().areAnnotatedWith(org.springframework.transaction.annotation.Transactional.class)
            .or().areAnnotatedWith(jakarta.transaction.Transactional.class)
            .or().areDeclaredInClassesThat().areAnnotatedWith(
                    org.springframework.transaction.annotation.Transactional.class)
            .should(new ArchCondition<JavaMethod>("call " + HTTP_CALL.getDescription()) {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    method.getMethodCallsFromSelf().stream().filter(HTTP_CALL)
                            .forEach(call -> events.add(SimpleConditionEvent.satisfied(method, call.getDescription())));
                }
            })
            .as("no HTTP client call inside a @Transactional method");

    private static final DescribedPredicate<JavaClass> PIPELINE_RUN_ENTITY =
            new DescribedPredicate<>("a JPA entity mapped to pipeline_run") {
                @Override
                public boolean test(JavaClass c) {
                    return c.isAnnotatedWith(jakarta.persistence.Entity.class)
                            && c.tryGetAnnotationOfType(jakarta.persistence.Table.class)
                                    .map(t -> t.name().equalsIgnoreCase("pipeline_run"))
                                    .orElse(false);
                }
            };

    /** pipeline_run is written only through JdbcClient, so its entity is read-only (README §5.1 rule 2). */
    public static final ArchRule PIPELINE_RUN_ENTITY_IS_READ_ONLY = classes()
            .that(PIPELINE_RUN_ENTITY)
            .should().beAnnotatedWith(org.hibernate.annotations.Immutable.class)
            .as("the pipeline_run entity is @Immutable; writes go through JdbcClient");

    /** SQL that differs between the databases lives only in SqlDialect (ADR-003 rule 4). */
    public static final ArchRule VENDOR_BRANCHING_ONLY_IN_SQL_DIALECT = noClasses()
            .that().doNotHaveFullyQualifiedName("com.crisil.apiorch.persistence.SqlDialect")
            .should().callMethod(DatabaseMetaData.class, "getDatabaseProductName")
            .orShould().callMethod(DatabaseMetaData.class, "getDriverName")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.boot.jdbc.DatabaseDriver")
            .as("nothing outside SqlDialect branches on the database vendor");
}
