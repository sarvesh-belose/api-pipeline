# US-7.6 Encrypt working context and webhook payloads
**Epic:** E7 Activity logging & data protection · **Priority:** Must · **Size:** M · **Depends on:** US-4.3 · **Release:** R1

## User story
As a **Security Officer**, I want every unmasked piece of run data at rest (inputs, step
responses, outputs and caller-webhook payloads) encrypted from the first release, so that a
database dump or backup never exposes PAN, Aadhaar or other personal data in clear text.

## Description
`run_context` holds unmasked inputs, step responses and the output, which mapping, resume and the
caller's output need. `outbox_event.payload` holds the unmasked run resource sent to the
caller. Both are encrypted with **AES-256-GCM**:
- **Per-run keys:** each run uses a key derived with **HKDF-SHA256** from the active master key,
  with the `runId` as salt. With a per-run key and a random 96-bit IV, the number of messages
  encrypted under one key stays tiny, far below the GCM safety limit, whatever the total volume.
- **Stored value format:** `v1:<kid>:<base64(iv|ciphertext|tag)>`.
- **Binding to the row:** the AAD is `runId|entryKey`, so ciphertexts can't be swapped between
  rows.
- **Rotation:** master keys are identified by `kid`. New data uses `apiorch.context.active-kid`,
  and old kids keep decrypting while they stay configured.

Masked tables (`step_attempt`, `callback_inbox`, `run_exchange`, `run_event`) aren't encrypted,
because they hold no unmasked values (US-7.3).

**Out of scope:** retention and purge (US-7.8), KMS integration (keys are resolved by reference,
so KMS can be added later), and database-level TDE.

## Acceptance criteria
- [ ] **AC1:** Given encryption keys configured, when a run stores its input and step responses,
  then `run_context.content` in the database starts with `v1:<kid>:` and isn't readable JSON,
  and `key_id` is set. Reading through the application returns the original JSON, and mapping,
  resume and the run output work unchanged.
- [ ] **AC2:** Given a caller webhook is enqueued (US-6.5), then `outbox_event.payload` is
  encrypted the same way, with AAD `outbox|eventId`, and the dispatcher decrypts it just before
  sending.
- [ ] **AC3:** Given key rotation, where `apiorch.context.encryption-keys.k2` is added and
  `active-kid=k2`, when new data is written, then it uses `k2`, and existing `k1` values still
  decrypt while `k1` remains configured. A startup report lists how many rows still use each
  old kid.
- [ ] **AC4:** Given a tampered ciphertext, or one moved to another row (AAD mismatch), when it
  is read, then decryption fails, an ERROR is logged, and the run fails with
  `CONTEXT_DECRYPTION_FAILED` instead of using corrupt data.
- [ ] **AC5:** Given any profile other than `local` without encryption keys, when the app starts,
  then startup fails. Given `local` without keys, encryption is disabled with a WARN, and values
  are stored with the prefix `plain:`.
- [ ] **AC6:** Given 1,000 runs, when their entries are encrypted, then each run uses a distinct
  derived key. This is verified by checking that HKDF output differs per `runId` for the same
  master key, and that no derived key is persisted.

## Technical details
**Components** (`com.crisil.apiorch.persistence`)
- `ContextCipher.encrypt(runId, entryKey, plaintext)` / `decrypt(...)`:
  - Derive the per-run key with `HKDF-SHA256(masterKey[kid], salt = runId bytes, info =
    "apiorch-context-v1")`, then run `Cipher("AES/GCM/NoPadding")` with a 128-bit tag, a
    `SecureRandom` 96-bit IV and AAD `runId|entryKey`.
  - HKDF is implemented with `javax.crypto.Mac` (HmacSHA256), with no extra library.
  - Derived keys are cached per run in memory only for the duration of an `advance` call.
- Master keys are resolved through `SecretResolver` (US-2.2) from
  `apiorch.context.encryption-keys.<kid>`, for example `env:APIORCH_SECRET_CTX_K1`. Each is a
  base64 32-byte value.
- `engine.RunContextStore` (US-4.3) and `outbox.OutboxWriter` and `OutboxDispatcher` (US-6.5)
  call the cipher.

**Data:** `run_context.content` (encrypted) and `key_id`; `outbox_event.payload` (encrypted).

**Configuration:** `apiorch.context.encryption-keys.<kid>` and `apiorch.context.active-kid`.

**Test notes**
- `ContextCipherTest` covers round-trip, AAD mismatch, tamper, two-kid rotation, plain mode and
  per-run key derivation (AC3–AC6).
- `EncryptedContextIT` runs a pipeline, reads the raw column, checks the prefix and that mapping
  still works, and covers the outbox payload once US-6.5 exists (AC1, AC2).
