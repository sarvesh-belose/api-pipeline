package com.crisil.apiorch.observability;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Fast tier: the activity log on H2. The slow-tier run on SQL Server is the evidence for AC2 (ADR-003 rule 6). */
@SpringBootTest
@ActiveProfiles("test")
class ActivityRecorderIT extends ActivityRecorderContract {
}
