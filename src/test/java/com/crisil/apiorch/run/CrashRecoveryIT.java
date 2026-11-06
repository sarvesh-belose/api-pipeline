package com.crisil.apiorch.run;

import testsupport.cluster.ClusterTestSupport;

/** Fast tier: the crash, fencing and sweep scenarios on two nodes sharing a named H2 database. */
class CrashRecoveryIT extends CrashRecoveryContract {

    @Override
    ClusterTestSupport newCluster() {
        return ClusterTestSupport.onH2("crash_" + System.nanoTime());
    }
}
