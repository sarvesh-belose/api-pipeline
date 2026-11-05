package archfixtures.engine;

import org.springframework.web.client.RestClient;

/** Violates ENGINE_PACKAGES_ARE_PURE. */
public class EngineUsesRestClient {
    RestClient client;
}
