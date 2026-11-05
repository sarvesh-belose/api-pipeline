package com.crisil.apiorch.persistence;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Fast tier: lease SQL on H2 (syntax only; H2 ignores lock hints, ADR-003 F3). */
@SpringBootTest
@ActiveProfiles("test")
class LeaseSqlIT extends LeaseSqlContract {
}
