package com.crisil.apiorch.observability;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Fast tier: attempt rows on H2. */
@SpringBootTest
@ActiveProfiles("test")
class AttemptWriterIT extends AttemptWriterContract {
}
