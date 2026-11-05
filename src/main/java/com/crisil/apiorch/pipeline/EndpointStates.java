package com.crisil.apiorch.pipeline;

import java.util.HashMap;
import java.util.Map;

import com.crisil.apiorch.endpoint.EndpointRepository;

/** Registered endpoint names and whether each is enabled, as the validator needs them (US-3.2 AC4). */
final class EndpointStates {

    private EndpointStates() {
    }

    static Map<String, Boolean> of(EndpointRepository endpoints) {
        Map<String, Boolean> states = new HashMap<>();
        endpoints.findAllStates().forEach(s -> states.put(s.getName(), s.getEnabled()));
        return states;
    }
}
