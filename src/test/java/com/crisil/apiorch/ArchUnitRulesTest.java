package com.crisil.apiorch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.time.Instant;
import java.time.LocalDateTime;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * README §13 architecture rules, skeleton subset (US-1.1 AC11). Sprint 1 adds the remaining rules and one
 * deliberately violating fixture per rule.
 */
@AnalyzeClasses(packages = "com.crisil.apiorch", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchUnitRulesTest {

    /** Jackson 2 is the only line (ADR-003 F7). */
    @ArchTest
    static final ArchRule noJackson3 = noClasses().should().dependOnClassesThat().resideInAPackage("tools.jackson..");

    /** Time comes from the injected Clock (README §13). */
    @ArchTest
    static final ArchRule noWallClockOutsideTimeConfig = noClasses()
            .that().resideOutsideOfPackages("com.crisil.apiorch.config..", "com.crisil.apiorch.support.h2..")
            .should().callMethod(Instant.class, "now")
            .orShould().callMethod(LocalDateTime.class, "now")
            .orShould().callMethod(System.class, "currentTimeMillis");

    /** The H2 shim is loaded by H2 only (ADR-003 rule 3). */
    @ArchTest
    static final ArchRule nothingImportsTheH2Shim = noClasses()
            .that().resideOutsideOfPackage("com.crisil.apiorch.support.h2..")
            .should().dependOnClassesThat().resideInAPackage("com.crisil.apiorch.support.h2..");

    /** Engine packages stay pure so the dry run can reuse them (README §13). */
    @ArchTest
    static final ArchRule enginePackagesArePure = noClasses()
            .that().resideInAnyPackage("..engine..", "..expression..", "..definition..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.web.client..", "java.net.http..", "org.springframework.jdbc..",
                    "org.springframework.data.repository..");
}
