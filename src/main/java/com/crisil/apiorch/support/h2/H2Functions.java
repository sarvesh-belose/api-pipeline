package com.crisil.apiorch.support.h2;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * SQL Server functions that H2's MSSQLServer mode lacks, registered by {@code db/migration/h2/V0_1__h2_mssql_shims.sql}
 * so the common migrations and engine SQL stay SQL Server text (ADR-003 F2, rule 3). Only ever loaded by H2;
 * engine code must not import this package.
 */
public final class H2Functions {

    private H2Functions() {
    }

    /** {@code SYSUTCDATETIME()}: DATETIME2 in UTC, independent of the JVM zone. */
    public static LocalDateTime sysUtcDateTime() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
