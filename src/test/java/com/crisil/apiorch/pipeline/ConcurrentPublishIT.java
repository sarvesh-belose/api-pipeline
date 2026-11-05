package com.crisil.apiorch.pipeline;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Fast tier: the publish race on H2 (MVCC row locks with {@code FOR UPDATE}). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConcurrentPublishIT extends ConcurrentPublishContract {
}
