package com.crisil.apiorch.step;

import java.util.List;

import com.crisil.apiorch.observability.AttemptRecord;

/**
 * One attempt of a step, as the executor saw it (US-4.3, US-7.2). The coordinator stores the row and the
 * {@code RESPONSE_RECEIVED} and {@code TOKEN_REFRESHED} events in the same fenced transaction as the step outcome.
 *
 * @param record           the complete row; its {@code eventSeq} is the attempt's {@code REQUEST_SENT}
 * @param responseReceived whether a response status line arrived
 * @param tokenRefreshes   endpoints whose rejected OAuth2 token was refreshed during this attempt (US-2.2)
 */
public record StepAttempt(AttemptRecord record, boolean responseReceived, List<String> tokenRefreshes) {
}
