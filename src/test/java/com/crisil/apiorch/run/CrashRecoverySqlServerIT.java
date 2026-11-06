package com.crisil.apiorch.run;

import org.junit.jupiter.api.Tag;

import testsupport.cluster.ClusterTestSupport;
import testsupport.db.SharedSqlServer;

/** Slow tier: the same scenarios on SQL Server, where the row locks and the claim race are real (ADR-003 F3). */
@Tag("slow")
class CrashRecoverySqlServerIT extends CrashRecoveryContract {

    @Override
    ClusterTestSupport newCluster() {
        SharedSqlServer.MSSQL.start();
        return ClusterTestSupport.onSqlServer(SharedSqlServer.MSSQL);
    }
}
