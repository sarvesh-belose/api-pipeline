package testsupport.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A settable {@link Clock} for tests (R1 plan §7.3): deadlines, backoff, token expiry and sweepers become
 * deterministic. Register it as the {@code Clock} bean; {@code TimeConfig} backs off. Database time
 * ({@code SYSUTCDATETIME()}) is not affected, by design (README §13).
 */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    public MutableClock(Instant start) {
        this(new AtomicReference<>(start), ZoneOffset.UTC);
    }

    private MutableClock(AtomicReference<Instant> now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    public static MutableClock at(String isoInstant) {
        return new MutableClock(Instant.parse(isoInstant));
    }

    public void set(Instant instant) {
        now.set(instant);
    }

    public Instant advance(Duration by) {
        return now.updateAndGet(t -> t.plus(by));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(now, zone);
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
