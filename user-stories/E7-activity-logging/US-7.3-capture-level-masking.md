# US-7.3 Configure capture level and mask sensitive data
**Epic:** E7 Activity logging & data protection · **Priority:** Must · **Size:** M · **Depends on:** US-7.2 · **Release:** R1

## User story
As a **Security Officer**, I want each pipeline (and step) to control how much of each exchange
is stored, and every sensitive field and header masked before anything is persisted or logged,
so that we keep a full audit trail without exposing PAN, Aadhaar, passwords or tokens.

## Description
**Capture levels**, set per pipeline in `logging.capture` and overridable per step. Every level
records an attempt row, so the call sequence, response times and sizes are **always** kept:

| Level | `step_attempt` / `callback_inbox` / `run_exchange` content |
|---|---|
| `METADATA` (default) | Method, URL (query masked), status, outcome, duration, sizes, error, circuit state |
| `HEADERS` | `METADATA` plus masked request and response headers |
| `FULL` | `HEADERS` plus masked, size-capped request and response bodies |

**`FULL` is a conscious choice, not a default.** At the NFR load it writes roughly 170 GB/day of
masked bodies (README §14) into the same database that serves run state, synchronously in each
attempt's critical path. A pipeline that needs it says so; one that never looked at the setting
gets the call sequence, response times and sizes — which is most of what "what happened to
transaction X?" needs — without the storage bill. Because the headline requirement does include
payload capture (README §4), **a definition that does not set `logging.capture` is published with
the warning `CAPTURE_LEVEL_NOT_SET`** (US-3.2), naming the level in force, and the authoring kit
template (US-8.3) sets it explicitly. Silence is never taken as consent to either level.

Payload capture stays **synchronous and transactional**: an attempt's masked bodies commit with the
attempt row. Writing them through an async queue would make the audit trail lossy on a crash, which
costs more than it saves.

**Masking** happens before persistence and logging:
- **Headers:** `apiorch.logging.always-mask-headers`, plus an endpoint's API-key header name
  (US-2.2).
- **Body fields:** `apiorch.logging.always-mask-keys`, plus the pipeline and step `maskKeys`.
  - A plain key name matches at any depth, case-insensitive.
  - A dotted path such as `customer.dob` or `applicants[*].pan` matches from the root.
- **URL query parameters:** masked using the same key names, and `t` (the callback token) always.
- **Form-urlencoded bodies:** masked by key.
- **Any body that can't be parsed and masked** (XML, text, binary, malformed JSON, or larger
  than `max-response-bytes`) is stored **only** as a placeholder,
  `<unparseable body: N bytes, content-type>`. Raw prefixes are never stored. Whether XML must
  be logged with masking is open question Q3.

The masked value is `***`.

**Masking applies to:** `step_attempt`, `callback_inbox`, `run_exchange`, `run_event.details`,
log lines in **every** profile, and the timeline, attempt, exchange and dry-run APIs.

**Masking does not apply to:** `run_context` (working data, encrypted by US-7.6), the run output
returned to its own caller, and the outbox payload (encrypted by US-7.6).

## Acceptance criteria
- [ ] **AC1:** Given `maskKeys: ["pan","aadhaar"]` and the request body
  `{"name":"Asha","pan":"ABCDE1234F","kyc":{"aadhaar":"1234-5678-9012"}}`, when stored, then
  `request_body` is `{"name":"Asha","pan":"***","kyc":{"aadhaar":"***"}}`, while the downstream
  received the unmasked values.
- [ ] **AC2:** Given `maskKeys: ["applicants[*].pan"]` and a body with
  `applicants: [{pan:…},{pan:…}]`, then both values are masked. A top-level `pan` is **not**
  masked by this path rule unless `pan` is also a plain key.
- [ ] **AC3:** Given `Authorization`, `Cookie`, `X-Api-Key` and `X-Callback-Token` headers, when
  stored or logged, then their values are always `***`, and the callback URL is stored and
  logged as `…?t=***`. This holds in both the JSON (`prod`) and pattern (`local`/`test`) log
  layouts.
- [ ] **AC4:** Given pipeline `capture: METADATA` and step api3 `capture: FULL`, when the run
  executes, then api1's rows have null header and body columns, and api3's rows have masked
  bodies. **All** steps have attempt rows with sizes and durations, so the timeline (US-7.4) is
  complete at every level.
- [ ] **AC5:** Given a `text/xml` response, a malformed JSON response, or a JSON response above
  `max-response-bytes`, when stored at `FULL`, then the body column holds
  `<unparseable body: N bytes, content-type>` and nothing else. Given a form body with
  `password=…`, that value is masked.
- [ ] **AC6:** Given `apiorch.logging.include-bodies-in-logs=true` and `FULL`, when an attempt is
  logged, then the line contains the **masked**, truncated bodies (at most 4 KB). With the
  default `false`, log lines contain no bodies.
- [ ] **AC7:** Given an invalid `maskKeys` entry, when published, then it is rejected with
  `MASK_KEY_INVALID` (US-3.2). Given a `capture` value other than `METADATA`, `HEADERS` or
  `FULL` (for example `"NONE"`), it is rejected as `SCHEMA_INVALID`.
- [ ] **AC8 (default is explicit):** Given a definition with no `logging.capture` at pipeline or
  step level, when validated or published, then it succeeds with the warning
  `CAPTURE_LEVEL_NOT_SET`, whose message names the effective level
  (`apiorch.logging.default-capture`, `METADATA`). Given a pipeline that sets `capture: FULL`, no
  warning is raised and bodies are captured as in AC1.
- [ ] **AC9 (the `message` field is masked too):** Given a log statement that renders a sensitive
  value into its text, for example `log.info("login {}", keyValue("password", "s3cret"))`, when
  it is written in either layout, then the `password` **field** is `***` **and** the `message`
  text reads `login password=***`. This is required because field-path masking does not reach the
  free-text `message` (ADR-001 F5, still valid per ADR-003; reproduced in the stack probe).
- [ ] **AC10 (canary):** Given the integration suite runs, when `MaskingEndToEndIT` drives a full
  run whose input, headers, downstream responses and endpoint secrets carry known canary values
  (for example `CANARY-PAN-0001`, `CANARY-SECRET-0002`), then **no captured log line** in either
  layout, and no row in `step_attempt`, `run_exchange` or `run_event`, contains any canary value.
  The build fails otherwise. This catches every leak path at once, including future ones.

## Technical details
**Components** (`com.crisil.apiorch.observability`)
- `MaskingPolicy` is the compiled set per step: header names, plain keys and path matchers,
  merged with the globals, built once per `CompiledPipeline`.
- `PayloadMasker`:
  - `maskJson(JsonNode, policy)` returns a deep copy and never mutates its input.
  - `maskForm`, `maskHeaders` and `maskUrl` handle the other shapes.
  - `placeholder(bytes, contentType)` is used for anything else.
  - Truncation happens after masking, at a UTF-8 boundary.
- All writers call `PayloadMasker`: `AttemptWriter`, `CallbackService`, `RunExchangeWriter`
  (US-7.7), `ActivityRecorder`, `DryRunService` and the timeline assembler.
- **Log safety net**, in every profile:
  - `prod` JSON uses logstash-logback-encoder **8.1**'s `MaskingJsonGeneratorDecorator`, declared
    with the 8.1 element (ADR-003 F11):
    ```xml
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
      <jsonGeneratorDecorator class="net.logstash.logback.mask.MaskingJsonGeneratorDecorator">
        <defaultMask>***</defaultMask>
        <path>password</path> <!-- one <path> per always-mask key -->
        <value>(?&lt;=[?&amp;]t=)[^&amp;\s]+</value>
        <value>(?&lt;=Bearer )\S+</value>
        <value>(?i)(?&lt;=\b(password|secret|token|otp|pin)=)[^\s\x2C\x7D]+</value>
      </jsonGeneratorDecorator>
    </encoder>
    ```
    **Paths** mask structured fields. **Value regexes** are what mask the same keys when they
    appear inside the free-text `message` (AC9). In XML, 8.1 cuts a `<value>` regex at a bare `,`
    or `}` (`PatternSyntaxException` at startup), so a literal comma or closing brace is written
    `\x2C` or `\x7D` (ADR-003 F11). Code that builds the encoder programmatically calls
    `encoder.setJsonGeneratorDecorator(…)`, and must `start()` the decorator first (NPE otherwise),
    because it is a logback `LifeCycle` and only XML configuration starts it automatically.
  - `local`/`test` patterns use a custom `MaskingMessageConverter` (`%maskedMsg`) with the same
    value regexes.
  - The value regexes are generated from `apiorch.logging.always-mask-keys` at startup, so a key
    added to that list is covered in fields and in message text alike.
  - The coding rule in README §13 (no sensitive values in `{}` placeholders) remains the first
    line of defence. The regexes are the net, and the AC10 canary is the proof.
- The capture level resolves as step `logging.capture`, then pipeline `logging.capture`, then
  `apiorch.logging.default-capture`.

**Configuration** (README §11): `apiorch.logging.default-capture` (`METADATA`),
`default-max-body-bytes`,
`always-mask-headers`, `always-mask-keys` and `include-bodies-in-logs`.

**Capacity note:** at `FULL` capture, plan about 4 KB per attempt (README §14), and remember that
those bytes are written synchronously into the OLTP database on the step's critical path. Set
`FULL` on the pipelines whose audit obligation needs it, and leave the rest at `METADATA`.
US-8.2 measures the real figure at both levels (AC4 there).

**Test notes**
- `PayloadMaskerTest` is table-driven: keys at depth, case-insensitivity, `[*]` paths, arrays,
  form bodies, URLs, masking before truncation, and the placeholder rules.
- `CaptureLevelIT` covers the three levels with pipeline and step overrides, timeline completeness
  (AC4), and the unset-capture warning with the effective default (AC8).
- `MaskingEndToEndIT` checks that the WireMock request is unmasked while the database, logs in
  both layouts and the timeline API are masked (AC1–AC3, AC6), and runs the canary scan over
  every captured log line and the three tables (AC10).
- `LogMessageMaskingTest` encodes a `{}`-placeholder event with the production encoder
  configuration and asserts both the field and the message text are masked (AC9).
- `UnparseableBodyIT` covers AC5.
- `CaptureValidationTest` covers AC7.
