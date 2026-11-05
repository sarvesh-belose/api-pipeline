package com.crisil.apiorch.persistence;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Fast tier: migrations on H2 in MSSQL mode. */
@SpringBootTest
@ActiveProfiles("test")
class MigrationIT extends MigrationContract {
}
