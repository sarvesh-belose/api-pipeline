package com.crisil.apiorch.config;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * This node's identity, {@code apiorch.instance-id} (README §11): written to {@code pipeline_run.owner_instance}
 * and {@code run_event.instance_id} (US-5.5). Defaults to the host name, or a random UUID when there is none.
 */
@Component
public class InstanceIdentity {

    private final String id;

    public InstanceIdentity(@Value("${apiorch.instance-id:${HOSTNAME:}}") String configured) {
        String value = configured == null || configured.isBlank() ? UUID.randomUUID().toString() : configured.strip();
        this.id = value.length() > 128 ? value.substring(0, 128) : value;
    }

    public String id() {
        return id;
    }
}
