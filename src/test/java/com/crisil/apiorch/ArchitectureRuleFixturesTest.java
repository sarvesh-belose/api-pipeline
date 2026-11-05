package com.crisil.apiorch;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;

import archfixtures.MutablePipelineRun;
import archfixtures.TransactionalHttpCall;
import archfixtures.UsesJackson3;
import archfixtures.UsesWallClock;
import archfixtures.VendorBranch;
import archfixtures.engine.EngineImportsH2Shim;
import archfixtures.engine.EngineUsesRestClient;

/** US-1.1 AC11: each architecture rule fires on its deliberately violating fixture. */
class ArchitectureRuleFixturesTest {

    private static void assertFires(ArchRule rule, Class<?>... fixtures) {
        JavaClasses classes = new ClassFileImporter().importClasses(fixtures);
        assertThatThrownBy(() -> rule.check(classes)).isInstanceOf(AssertionError.class);
    }

    @Test
    void jackson3Import() {
        assertFires(ArchitectureRules.NO_JACKSON_3, UsesJackson3.class);
    }

    @Test
    void wallClock() {
        assertFires(ArchitectureRules.NO_WALL_CLOCK, UsesWallClock.class);
    }

    @Test
    void engineImportingTheH2Shim() {
        assertFires(ArchitectureRules.NOTHING_IMPORTS_THE_H2_SHIM, EngineImportsH2Shim.class);
    }

    @Test
    void engineUsingAnHttpClient() {
        assertFires(ArchitectureRules.ENGINE_PACKAGES_ARE_PURE, EngineUsesRestClient.class);
    }

    @Test
    void httpCallInsideATransaction() {
        assertFires(ArchitectureRules.NO_HTTP_CALL_IN_TRANSACTION, TransactionalHttpCall.class);
    }

    @Test
    void writablePipelineRunEntity() {
        assertFires(ArchitectureRules.PIPELINE_RUN_ENTITY_IS_READ_ONLY, MutablePipelineRun.class);
    }

    @Test
    void vendorBranchOutsideSqlDialect() {
        assertFires(ArchitectureRules.VENDOR_BRANCHING_ONLY_IN_SQL_DIALECT, VendorBranch.class);
    }
}
