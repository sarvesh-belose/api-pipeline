package testsupport.web;

/** Endpoint request bodies for tests (US-2.1). */
public final class EndpointJson {

    private EndpointJson() {
    }

    public static String minimal(String name, String baseUrl) {
        return """
                {"name":"%s","baseUrl":"%s","connectTimeoutMs":2000,"readTimeoutMs":5000}""".formatted(name, baseUrl);
    }

    public static String withHealth(String name, String baseUrl, String healthPath, int readTimeoutMs) {
        return """
                {"name":"%s","baseUrl":"%s","healthPath":"%s","connectTimeoutMs":1000,"readTimeoutMs":%d}"""
                .formatted(name, baseUrl, healthPath, readTimeoutMs);
    }

    public static String full(String name, String baseUrl) {
        return """
                {"name":"%s","description":"KYC verification (DC-2)","baseUrl":"%s","healthPath":"/health",
                 "connectTimeoutMs":2000,"readTimeoutMs":5000,"httpVersion":"HTTP_1_1","enabled":true,
                 "auth":{"type":"NONE"},
                 "resilience":{"circuitBreaker":{"slidingWindowSize":40,"minimumCalls":20},
                               "retry":{"maxAttempts":5,"backoff":"EXPONENTIAL","initialMs":100,"maxMs":1000}}}"""
                .formatted(name, baseUrl);
    }
}
