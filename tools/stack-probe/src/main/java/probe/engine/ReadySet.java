package probe.engine;

import java.util.List;

/** Pure engine class; ArchUnit asserts it never reaches HTTP or JDBC (README §13). */
public final class ReadySet {
    private ReadySet() {
    }

    public static List<String> roots(List<String> steps) {
        return steps.isEmpty() ? List.of() : List.of(steps.get(0));
    }
}
