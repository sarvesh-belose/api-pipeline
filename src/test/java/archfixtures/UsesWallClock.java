package archfixtures;

import java.time.Instant;

/** Violates NO_WALL_CLOCK. */
public class UsesWallClock {
    Instant now() {
        return Instant.now();
    }
}
