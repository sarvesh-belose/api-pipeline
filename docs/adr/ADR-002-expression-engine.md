# ADR-002: Expression engine for pipeline mappings

| | |
|---|---|
| **Status** | Proposed. The tech lead accepts it at the Sprint 0 review (R1 plan §3, gate G2); the Business Owner signs off on the regex restriction (D6) |
| **Date** | 2026-11-04 |
| **Gate** | G2 in [R1-implementation-plan.md](../R1-implementation-plan.md) §3; the go/no-go table in US-3.1 |
| **Decision** | **Use `com.dashjoin:jsonata` 0.9.10 behind a `GuardedJsonataEvaluator`** (US-3.1 outcome row 2). The restricted-syntax fallback is not needed |

## Context

Pipeline expressions are semi-trusted code. They are authored by a person, published through an
API, and evaluated on every run on a thread of the fixed-size `step-io` pool (US-4.3). US-3.1
made the engine a gate with three possible outcomes:
1. The library bounds itself: use it.
2. It can be aborted cooperatively: use it behind a guard.
3. Neither: fall back to a restricted syntax.

The probe ran on JDK 17 with `-Xss1m -Xmx1g`, on a 16-core machine.

## What the probe found

### Bounds (the hostile suite)

The library offers `Frame.setRuntimeBounds(timeoutMs, maxDepth)`, implemented by `Timebox`. It
is **cooperative**: on every AST node entry and exit it checks the depth and the wall-clock time
since the frame was bounded, and throws `JException`. All cases ran with a 100 ms timeout and
depth 200, under a 10 s harness watchdog.

| # | Hostile expression | Outcome |
|---|---|---|
| H1 | Unbounded recursion | Aborted by bounds in 116 ms |
| H2 | Deep finite recursion (100k) | Aborted in 104 ms |
| H3 | Cross product 3000 × 3000 | Aborted in 107 ms |
| H4 | `$reduce` over a 10M range | Aborted in 104 ms |
| H5 | `$sort` of 2M with a comparator lambda | Aborted in 102 ms, but the `JException` arrives **wrapped in a `RuntimeException`** |
| H6 | `$sum([1..10000000])` (single built-in) | Aborted in **451 ms**: it overshoots, because building the range happens inside one node |
| H7 | `$pad('x', 50000000)` (single built-in) | **Not stopped**: still running after the 10 s watchdog |
| H8 | String doubling ×30 (exponential memory, shallow) | Aborted in 124 ms |
| H9 | Regex backtracking (`/^(a+)+$/`, `/^(a\|a)+$/`, `/(a\|aa)+$/`) | Completed in 2–7 ms; the classic patterns did not blow up on this JVM |
| H10 | 5000 nested parentheses | **`StackOverflowError` at compile**, not a `JException` |

**Conclusion:** the library's bounds stop everything that runs **through the evaluator**. They
can't stop a single built-in that does unbounded work on one call (H7), or the parser (H10).
Both gaps are closed by guards, proven in the probe:

| Guard | Proof |
|---|---|
| Override `$pad` via `registerFunction` with an argument cap | The H7 expression now fails in **27 ms** with `D3999` |
| Pre-compile nesting check (limit 64) | H10 is rejected before the parser ever runs |
| Unwrap the cause chain to find `JException` | H5's root cause is the timeout `JException` |

### Thread safety

- **Bytecode:** `evaluate(Object, Frame)` and the internal `evaluate`/`_evaluate` **write the
  instance fields** `input` and `environment` on every call, and compilation sets `errors` and
  `timestamp`. A compiled `Jsonata` is therefore **not thread-safe by construction**: there is
  no happens-before guarantee between two threads evaluating the same instance.
- **Behaviour:** with a sufficient time budget, **0 wrong results in 480,000 concurrent
  evaluations** (32 threads, three runs) on a shared instance, including root (`$$`) reads,
  and 0 with pooled instances. No corruption was observed, but nothing in the library promises
  that, and the cost of not relying on it is tiny (below). The decision is to **never share a
  compiled instance between concurrent evaluations**.

### The time budget is wall-clock

`Timebox` uses `System.currentTimeMillis()`, so scheduling delays and GC pauses count against
the budget. Under 32-thread load, trivially cheap expressions (≈10 µs) were aborted spuriously:

| Budget | Spurious aborts per 160,000 evaluations |
|---|---|
| 100 ms | 30–70 |
| 250 ms | 0–8 |
| **1,000 ms** | **0** (three runs × two variants, 960,000 evaluations) |

A spurious abort fails a healthy step with `EXPRESSION_ERROR`, so the budget must sit far above
realistic evaluation time. At 1 s, a runaway expression still releases its `step-io` thread
within about a second.

### Numbers, nulls, errors, overhead

| Item | Observed |
|---|---|
| Large integer **passed through** (`big`, 12345678901234567) | Stays `Long`, **exact** |
| Large integer **in arithmetic** (`big + 0`) or `$number('12345678901234567')` | 12345678901234568: **precision lost** (double arithmetic) |
| Decimals (`0.1 + 0.2`) | 0.30000000000000004 (binary double) |
| JSON `null` in input | Preserved as Java `null`; round-trips to JSON `null` |
| Missing path | Undefined: omitted from objects, matching the DSL's "undefined omits the header or param" |
| Jackson 3 round trip (`convertValue` → evaluate → `valueToTree`) | Lossless for the types above |
| Compile errors | Code **and** position, e.g. `S0203` at 25 ("Expected } before end of expression") |
| Runtime errors | Code always; position sometimes `-1` (e.g. `T2001`) |
| Evaluate cost (realistic 5-field mapping, under bounds) | **9.5 µs** average, single thread |
| Compile cost | 7.7 ms cold (first ever), **22–28 µs** warm |

## Decision

`expression.GuardedJsonataEvaluator` implements the `ExpressionEvaluator` interface (US-4.3),
with these rules:

| # | Rule | Closes |
|---|---|---|
| D1 | **Per-call isolation.** Each compiled expression has a small object pool (`ConcurrentLinkedQueue<Jsonata>`). An evaluation borrows an instance, evaluates on a fresh `Frame`, and returns it. The pool grows to the concurrency that expression actually sees, typically at most `step-io.size`, at about 25 µs per extra instance | Thread safety |
| D2 | **Library bounds on every evaluation:** `setRuntimeBounds(apiorch.expression.timeout-ms = 1000, apiorch.expression.max-depth = 200)` | H1–H6, H8 |
| D3 | **Unbounded built-ins are overridden** on each pooled instance via `registerFunction`, with argument caps. `$pad` is capped at width 10,000 (proven). Sprint 1 audits every other built-in whose work is proportional to an argument rather than to the input, for example `$join`, `$split` and `$replace` with limits, `$formatNumber` and `$zip`, and caps or forbids each one. A slow-tier `BuiltinAbuseIT` runs each built-in with extreme arguments under a watchdog | H7 and its relatives |
| D4 | **Pre-compile static limits** (US-3.2): expression length ≤ `apiorch.expression.max-length` (4096) and bracket nesting ≤ `apiorch.expression.max-nesting` (64), checked **before** the parser runs. `StackOverflowError` is also caught on the admin compile path and reported as `EXPRESSION_INVALID` | H10 |
| D5 | **Exception mapping:** any `Throwable` whose cause chain contains `JException` becomes step error `EXPRESSION_ERROR` with the JSONata code, and position when ≥ 0. Budget and depth aborts increment `apiorch.expression.budget_exceeded` | H5 wrapping |
| D6 | **No regex literals in R1 expressions.** US-3.2 rejects `/…/` regex arguments with `REGEX_NOT_ALLOWED`. Java's regex engine backtracks, and although the classic patterns did not blow up here (H9), no in-process bound can interrupt a single `Matcher` call. String functions without regex (`$substring`, `$contains` with a string, `$split` with a string) cover the recipes in US-8.3. **The Business Owner must accept this**; it is revisited with a guarded matcher, if needed, after R1 | Regex risk |
| D7 | **Numbers:** pass-through keeps integers exact, but **arithmetic, `$number` and decimals use doubles**. The DSL reference (US-3.1) states that identifiers above 2^53 must not take part in arithmetic, and that monetary arithmetic must be done by the downstream system, not in an expression | Precision |

**Per-evaluation cost** of D1–D5 is pool borrow/return plus a fresh frame, on top of the ~10 µs
evaluation. Against the 20 ms-per-step budget in README §14, this is negligible.

## Options considered

| Option | Verdict |
|---|---|
| **JSONata behind `GuardedJsonataEvaluator`** | **Chosen.** Every hostile case that reaches the evaluator is stopped by the library, and the two gaps are closed by guards that the probe demonstrated |
| JSONata used directly (row 1 of US-3.1) | Rejected. H7 hangs a `step-io` thread indefinitely, H10 throws `StackOverflowError`, and shared instances are unsafe by construction |
| `RestrictedExpressionEvaluator` fallback (row 3) | Not needed. It stays documented in US-3.1 as the contingency if the D3 audit finds a built-in that can't be capped |
| Evaluate on a separate thread with a hard timeout | Rejected by US-3.1: it reinstates a thread per evaluation, and `Thread` interruption doesn't stop CPU-bound Java code anyway (H7 ignored `cancel(true)`) |

## Consequences

- **New configuration** (README §11):
  - `apiorch.expression.timeout-ms` (1000; validated 500–5000), `apiorch.expression.max-depth`
    (200), `apiorch.expression.max-length` (4096) and `apiorch.expression.max-nesting` (64);
  - **don't lower the timeout below about 1 s** without re-running the contention test.
- **DSL restriction:** no regex literals (D6), pending Business Owner sign-off.
- **Sprint 1 work** inside US-3.1/US-4.3: the built-in audit and `BuiltinAbuseIT` (D3), about
  1–2 dev-days.
- **The probe is reusable:** re-run it on any JSONata version change.

## Evidence

The probe is at
`C:\Users\SarveshB\AppData\Local\Temp\claude\C--Users-SarveshB--claude-spares-spare-Jp1Dup\9d63f4f6-5507-49a9-987a-edb99bc99845\scratchpad\expr-gate\`.
It holds `ExpressionGateTest` (thread safety, numbers, errors, overhead, hostile suite) and
`ExpressionGuardsTest` (race, `$pad` override, nesting pre-check, regex, wrapping). To reproduce,
run `mvn -B -ntp test`. Copy it into the repository (for example `tools/expression-probe/`) to
keep this gate reproducible.
