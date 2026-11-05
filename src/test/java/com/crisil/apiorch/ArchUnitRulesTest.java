package com.crisil.apiorch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** README §13 architecture rules on production code (US-1.1 AC11). */
@AnalyzeClasses(packages = "com.crisil.apiorch", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchUnitRulesTest {

    @ArchTest static final ArchRule noJackson3 = ArchitectureRules.NO_JACKSON_3;
    @ArchTest static final ArchRule noWallClock = ArchitectureRules.NO_WALL_CLOCK;
    @ArchTest static final ArchRule nothingImportsTheH2Shim = ArchitectureRules.NOTHING_IMPORTS_THE_H2_SHIM;
    @ArchTest static final ArchRule enginePackagesArePure = ArchitectureRules.ENGINE_PACKAGES_ARE_PURE;
    @ArchTest static final ArchRule noHttpCallInTransaction = ArchitectureRules.NO_HTTP_CALL_IN_TRANSACTION;
    @ArchTest static final ArchRule pipelineRunEntityIsReadOnly = ArchitectureRules.PIPELINE_RUN_ENTITY_IS_READ_ONLY;
    @ArchTest static final ArchRule vendorBranchingOnlyInSqlDialect =
            ArchitectureRules.VENDOR_BRANCHING_ONLY_IN_SQL_DIALECT;
}
