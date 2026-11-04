package probe;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * SQL Server functions that H2's MSSQLServer mode lacks, registered by db/migration/h2/V0_1__h2_mssql_shims.sql
 * so the common migrations and the engine SQL stay SQL Server text. Only ever loaded on H2.
 */
public final class H2Functions {

    private H2Functions() {
    }

    /** SYSUTCDATETIME(): DATETIME2 in UTC, independent of the JVM zone. */
    public static LocalDateTime sysUtcDateTime() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
