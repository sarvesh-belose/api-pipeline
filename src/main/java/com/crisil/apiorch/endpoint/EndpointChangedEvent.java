package com.crisil.apiorch.endpoint;

/** Published inside the transaction that changed an endpoint; listeners act after commit (US-2.1 AC4). */
public record EndpointChangedEvent(String name) {
}
