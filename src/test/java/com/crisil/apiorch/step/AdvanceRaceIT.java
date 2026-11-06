package com.crisil.apiorch.step;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Fast tier: the advance race on H2 ({@code FOR UPDATE}). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdvanceRaceIT extends AdvanceRaceContract {
}
