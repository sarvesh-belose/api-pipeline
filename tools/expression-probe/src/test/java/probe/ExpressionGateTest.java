package probe;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * G2 gate for com.dashjoin:jsonata 0.9.10. Findings are printed as "G2|..." lines and are the
 * evidence for ADR-002; assertions only guard the harness itself, so every case always reports.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExpressionGateTest {

    static final long TIMEOUT_MS = 100;
    static final int MAX_DEPTH = 200;
    static final JsonMapper MAPPER = JsonMapper.builder().build();

    static void report(String line) {
        System.out.println("G2|" + line);
    }

    /** Evaluates with the library's own runtime bounds on a fresh frame. */
    static Object boundedEval(Jsonata expr, Object input) {
        Jsonata.Frame frame = expr.createFrame();
        frame.setRuntimeBounds(TIMEOUT_MS, MAX_DEPTH);
        return expr.evaluate(input, frame);
    }

    // ---------------------------------------------------------------- thread safety

    record Outcome(int mismatches, int errors, long millis) {
    }

    static Outcome hammer(int threads, int perThread, java.util.function.IntFunction<Object> evalForId) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger mismatches = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        long t0 = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            final int base = t * perThread;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    int id = base + i;
                    try {
                        Object out = evalForId.apply(id);
                        Map<?, ?> m = (Map<?, ?>) out;
                        if (!String.valueOf(id).equals(String.valueOf(m.get("id")))
                                || !"001".equals(m.get("branch"))
                                || ((Number) m.get("n")).intValue() != (id % 5)) {
                            mismatches.incrementAndGet();
                        }
                    } catch (Throwable e) {
                        errors.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();
        return new Outcome(mismatches.get(), errors.get(), (System.nanoTime() - t0) / 1_000_000);
    }

    static Map<String, Object> inputFor(int id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("acct", "ACC-0012345");
        List<Integer> items = new ArrayList<>();
        for (int i = 0; i < id % 5; i++) {
            items.add(i);
        }
        m.put("items", items);
        return m;
    }

    static final String MAPPING = "{ 'id': id, 'branch': $substring(acct, 4, 3), 'n': $count(items) }";

    @Test
    @Order(1)
    void threadSafety() throws Exception {
        int threads = 32;
        int per = 2000;
        Jsonata shared = Jsonata.jsonata(MAPPING);
        Outcome a = hammer(threads, per, id -> shared.evaluate(inputFor(id)));
        report("thread-safety|shared instance, evaluate(input)|evals=" + threads * per + "|mismatches=" + a.mismatches()
                + "|errors=" + a.errors() + "|ms=" + a.millis());

        Jsonata shared2 = Jsonata.jsonata(MAPPING);
        Outcome b = hammer(threads, per, id -> boundedEval(shared2, inputFor(id)));
        report("thread-safety|shared instance, per-call frame + runtime bounds|evals=" + threads * per + "|mismatches="
                + b.mismatches() + "|errors=" + b.errors() + "|ms=" + b.millis());

        ThreadLocal<Jsonata> local = ThreadLocal.withInitial(() -> Jsonata.jsonata(MAPPING));
        Outcome c = hammer(threads, per, id -> boundedEval(local.get(), inputFor(id)));
        report("thread-safety|per-thread instance (ThreadLocal), per-call frame|evals=" + threads * per + "|mismatches="
                + c.mismatches() + "|errors=" + c.errors() + "|ms=" + c.millis());
        assertThat(c.mismatches() + c.errors()).as("baseline per-thread instances must be correct").isZero();
    }

    // ---------------------------------------------------------------- numbers, nulls, Jackson 3 round trip

    @Test
    @Order(2)
    void numbersNullsAndJacksonRoundTrip() {
        JsonNode node = MAPPER.readTree("{\"big\":12345678901234567,\"bigStr\":\"12345678901234567\","
                + "\"safe\":9007199254740991,\"a\":0.1,\"b\":0.2,\"nul\":null,\"arr\":[1,null,\"x\"]}");
        @SuppressWarnings("unchecked")
        Map<String, Object> input = MAPPER.convertValue(node, Map.class);
        String[] exprs = {"big", "big + 0", "bigStr", "safe", "safe + 0", "a + b", "nul", "arr", "{ 'x': nul, 'y': missing }",
            "$string(big)", "$number(bigStr)"};
        for (String e : exprs) {
            Object out = boundedEval(Jsonata.jsonata(e), input);
            JsonNode back = MAPPER.valueToTree(out == Jsonata.NULL_VALUE ? null : out);
            report("numbers|" + e + "|javaType=" + (out == null ? "null" : out == Jsonata.NULL_VALUE ? "NULL_VALUE"
                    : out.getClass().getSimpleName()) + "|value=" + out + "|asJson=" + back);
        }
    }

    // ---------------------------------------------------------------- error positions

    @Test
    @Order(3)
    void errorPositions() {
        String[][] cases = {
            {"compile: unclosed object", "{ 'a': steps.api1.body.id"},
            {"compile: bad token", "{ 'a': steps..id }"},
            {"runtime: string + number", "{ 'a': 'x' + 1 }"},
            {"runtime: unknown function", "{ 'a': $nosuch(1) }"},
            {"runtime: substring of number", "$substring(5, 1)"}};
        for (String[] c : cases) {
            try {
                boundedEval(Jsonata.jsonata(c[1]), Map.of());
                report("errors|" + c[0] + "|no error");
            } catch (JException e) {
                report("errors|" + c[0] + "|code=" + e.getError() + "|location=" + e.getLocation() + "|message="
                        + e.getMessage().replace('|', '/'));
            } catch (Throwable t) {
                report("errors|" + c[0] + "|non-JException " + t.getClass().getName() + ": " + t.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------- positive control and overhead

    @Test
    @Order(4)
    void realisticMappingPassesUnderBoundsAndCostsMicroseconds() {
        String expr = "{ 'customerId': steps.api1.body.id, 'branch': $substring(steps.api1.body.account.number, 4, 3), "
                + "'pan': steps.api1.body.account.holders[0].pan, 'ref': steps.api1.headers.Location, "
                + "'items': steps.api2.body.lines[qty > 0].{ 'sku': sku, 'total': qty * price } }";
        Map<String, Object> ctx = Map.of("steps", Map.of(
                "api1", Map.of("headers", Map.of("Location", "/accounts/77"), "body", Map.of("id", "C-77",
                        "account", Map.of("number", "ACC-0012345", "holders", List.of(Map.of("pan", "ABCDE1234F"))))),
                "api2", Map.of("body", Map.of("lines", List.of(Map.of("sku", "A", "qty", 2, "price", 3.5),
                        Map.of("sku", "B", "qty", 0, "price", 1))))));
        long c0 = System.nanoTime();
        Jsonata compiled = Jsonata.jsonata(expr);
        long compileUs = (System.nanoTime() - c0) / 1000;
        Object out = boundedEval(compiled, ctx);
        report("control|realistic mapping under bounds|result=" + out);
        for (int i = 0; i < 20_000; i++) {
            boundedEval(compiled, ctx);
        }
        int n = 100_000;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            boundedEval(compiled, ctx);
        }
        double avgUs = (System.nanoTime() - t0) / 1000.0 / n;
        report(String.format("overhead|compile=%dus (cold)|evaluate avg=%.1fus over %d runs, single thread", compileUs, avgUs, n));
        assertThat(((Map<?, ?>) out).get("branch")).isEqualTo("001");
    }

    // ---------------------------------------------------------------- hostile suite

    record Hostile(String id, String description, String source) {
    }

    static final List<Hostile> HOSTILE = List.of(
            new Hostile("H1", "unbounded recursion", "( $f := function($x){ $f($x) }; $f(1) )"),
            new Hostile("H2", "deep finite recursion (100k)", "( $f := function($n){ $n = 0 ? 0 : $f($n - 1) }; $f(100000) )"),
            new Hostile("H3", "cross product 3000x3000 (9M node evals)", "$count([1..3000].([1..3000].($ * 2)))"),
            new Hostile("H4", "$reduce over 10M range (iterative lambda)", "$reduce([1..10000000], function($a, $b){ $a + $b })"),
            new Hostile("H5", "$sort 2M with comparator lambda", "$count($sort([1..2000000], function($a, $b){ $a < $b }))"),
            new Hostile("H6", "single built-in: $sum over 10M range", "$sum([1..10000000])"),
            new Hostile("H7", "single built-in: $pad to 50M chars", "$length($pad('x', 50000000))"),
            new Hostile("H8", "string doubling x30 (exponential memory, shallow)",
                    "( $f := function($s, $n){ $n = 0 ? $length($s) : $f($s & $s, $n - 1) }; $f('ab', 30) )"),
            new Hostile("H9", "regex catastrophic backtracking", "$contains('aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!', /^(a+)+$/)"),
            new Hostile("H10", "deeply nested parentheses (5000) at compile", "(".repeat(5000) + "1" + ")".repeat(5000)));

    @Test
    @Order(5)
    void hostileSuite() throws Exception {
        report("hostile|bounds: timeout=" + TIMEOUT_MS + "ms maxDepth=" + MAX_DEPTH + " | watchdog 10s | -Xss1m -Xmx1g");
        for (Hostile h : HOSTILE) {
            ExecutorService one = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "hostile-" + h.id());
                t.setDaemon(true);
                return t;
            });
            long t0 = System.nanoTime();
            Callable<String> task = () -> {
                Jsonata expr;
                try {
                    expr = Jsonata.jsonata(h.source());
                } catch (Throwable t) {
                    return "REJECTED_AT_COMPILE " + t.getClass().getSimpleName() + ": " + abbreviate(t.getMessage());
                }
                try {
                    Object out = boundedEval(expr, Map.of());
                    return "COMPLETED result=" + abbreviate(String.valueOf(out));
                } catch (JException e) {
                    return "ABORTED_BY_BOUNDS code=" + e.getError() + " msg=" + abbreviate(e.getMessage());
                } catch (StackOverflowError e) {
                    return "STACK_OVERFLOW (not a JException)";
                } catch (OutOfMemoryError e) {
                    return "OUT_OF_MEMORY";
                } catch (Throwable t) {
                    return "OTHER " + t.getClass().getSimpleName() + ": " + abbreviate(t.getMessage());
                }
            };
            Future<String> f = one.submit(task);
            String outcome;
            try {
                outcome = f.get(10, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                f.cancel(true);
                outcome = "NOT_STOPPED (still running after 10s watchdog; cancel(true) sent)";
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            report("hostile|" + h.id() + "|" + h.description() + "|" + ms + "ms|" + outcome);
            one.shutdownNow();
            System.gc();
        }
    }

    static String abbreviate(String s) {
        if (s == null) {
            return "null";
        }
        s = s.replace('|', '/').replace('\n', ' ');
        return s.length() > 140 ? s.substring(0, 140) + "…" : s;
    }
}
