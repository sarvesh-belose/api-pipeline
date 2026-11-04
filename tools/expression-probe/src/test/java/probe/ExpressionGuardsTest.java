package probe;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import com.dashjoin.jsonata.JException;
import com.dashjoin.jsonata.Jsonata;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/** G2 part 2: proves the shared-instance race and tests the guards a GuardedJsonataEvaluator would need. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExpressionGuardsTest {

    static void report(String line) {
        System.out.println("G2|" + line);
    }

    static Object bounded(Jsonata e, Object input) {
        Jsonata.Frame f = e.createFrame();
        f.setRuntimeBounds(Long.getLong("g2.timeoutMs", 1000L), 200);
        return e.evaluate(input, f);
    }

    /** $$ reads the evaluation root, which the library keeps in a shared instance field. */
    static final String ROOT_EXPR = "{ 'id': id, 'root': $$.id, 'deep': [1,2,3].($$.id) }";

    static int raceMismatches(java.util.function.Function<Map<String, Object>, Object> eval) throws Exception {
        int threads = 32;
        int per = 5000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger bad = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int base = t * per;
            fs.add(pool.submit(() -> {
                go.await();
                for (int i = 0; i < per; i++) {
                    int id = base + i;
                    try {
                        Map<?, ?> m = (Map<?, ?>) eval.apply(Map.of("id", id));
                        String want = String.valueOf(id);
                        boolean ok = want.equals(String.valueOf(m.get("id"))) && want.equals(String.valueOf(m.get("root")))
                                && String.valueOf(m.get("deep")).equals("[" + want + ", " + want + ", " + want + "]");
                        if (!ok) {
                            if (bad.incrementAndGet() <= 3) {
                                report("race-detail|WRONG id=" + id + " got=" + m);
                            }
                        }
                    } catch (Throwable e) {
                        if (bad.incrementAndGet() <= 3) {
                            Throwable r = e;
                            while (r.getCause() != null && r.getCause() != r) {
                                r = r.getCause();
                            }
                            report("race-detail|THREW id=" + id + " " + e.getClass().getSimpleName() + " root="
                                    + r.getClass().getSimpleName() + ": " + String.valueOf(r.getMessage()).replace('|', '/'));
                        }
                    }
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : fs) {
            f.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();
        return bad.get();
    }

    @Test
    @Order(1)
    void sharedInstanceLeaksAcrossThreadsButPooledInstancesDoNot() throws Exception {
        Jsonata shared = Jsonata.jsonata(ROOT_EXPR);
        int sharedBad = raceMismatches(in -> bounded(shared, in));
        report("race|shared compiled instance reading $$|evals=160000|wrong-or-failed=" + sharedBad);

        ConcurrentLinkedQueue<Jsonata> poolQ = new ConcurrentLinkedQueue<>();
        AtomicInteger created = new AtomicInteger();
        int pooledBad = raceMismatches(in -> {
            Jsonata e = poolQ.poll();
            if (e == null) {
                e = Jsonata.jsonata(ROOT_EXPR);
                created.incrementAndGet();
            }
            try {
                return bounded(e, in);
            } finally {
                poolQ.offer(e);
            }
        });
        report("race|object pool (borrow/return, one evaluation per instance at a time)|evals=160000|wrong-or-failed="
                + pooledBad + "|instances created=" + created.get());
        assertThat(pooledBad).isZero();

        // warm compile cost, which decides whether pooling or recompiling is the cheaper isolation
        for (int i = 0; i < 2000; i++) {
            Jsonata.jsonata(ROOT_EXPR);
        }
        int n = 20000;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            Jsonata.jsonata(ROOT_EXPR);
        }
        report(String.format("overhead|warm compile avg=%.1fus", (System.nanoTime() - t0) / 1000.0 / n));
    }

    static String runWithWatchdog(String label, java.util.concurrent.Callable<Object> body) throws Exception {
        ExecutorService one = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, label);
            t.setDaemon(true);
            return t;
        });
        long t0 = System.nanoTime();
        Future<Object> f = one.submit(body);
        String outcome;
        try {
            outcome = "COMPLETED " + f.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            outcome = "NOT_STOPPED after 10s";
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            Throwable root = c;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            outcome = "THREW " + c.getClass().getSimpleName() + " (root " + root.getClass().getSimpleName() + ": "
                    + String.valueOf(root.getMessage()).replace('|', '/') + ")";
        }
        one.shutdownNow();
        return ((System.nanoTime() - t0) / 1_000_000) + "ms|" + outcome;
    }

    @Test
    @Order(2)
    void guardOverrideBuiltInPad() throws Exception {
        String src = "$length($pad('x', 50000000))";
        String outcome = runWithWatchdog("pad-guard", () -> {
            Jsonata e = Jsonata.jsonata(src);
            e.registerFunction("pad", (String s, Number width) -> {
                if (Math.abs(width.longValue()) > 10_000) {
                    throw new JException("D3999: $pad width above guard limit 10000", -1);
                }
                return s;
            });
            return bounded(e, Map.of());
        });
        report("guard|override $pad via registerFunction (width cap 10k)|" + outcome);
    }

    @Test
    @Order(3)
    void guardNestingPrecheckAvoidsStackOverflow() {
        String src = "(".repeat(5000) + "1" + ")".repeat(5000);
        int max = 0;
        int depth = 0;
        for (char c : src.toCharArray()) {
            if (c == '(' || c == '[' || c == '{') {
                max = Math.max(max, ++depth);
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            }
        }
        report("guard|nesting pre-check before compile|measured depth=" + max + "|rejected=" + (max > 64)
                + " (limit 64; compile never attempted)");
        assertThat(max).isGreaterThan(64);
    }

    @Test
    @Order(4)
    void regexCatastrophicBacktracking() throws Exception {
        String[] srcs = {
            "$contains('" + "a".repeat(32) + "!', /^(a|a)+$/)",
            "$match('" + "a".repeat(32) + "!', /(a|aa)+$/)"};
        for (String s : srcs) {
            report("regex|" + s.replace('|', '¦') + "|" + runWithWatchdog("regex", () -> bounded(Jsonata.jsonata(s), Map.of())));
        }
    }

    @Test
    @Order(5)
    void sortComparatorAbortIsWrapped() throws Exception {
        String outcome = runWithWatchdog("sort", () -> bounded(Jsonata.jsonata(
                "$count($sort([1..2000000], function($a, $b){ $a < $b }))"), Map.of()));
        report("wrapping|$sort comparator abort|" + outcome);
    }
}
