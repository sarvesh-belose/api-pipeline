package com.crisil.apiorch.persistence;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Fast tier: the schema on H2 in MSSQL mode. */
@SpringBootTest
@ActiveProfiles("test")
class SchemaIT extends SchemaContract {
}
