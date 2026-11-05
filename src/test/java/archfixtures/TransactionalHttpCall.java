package archfixtures;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/** Violates NO_HTTP_CALL_IN_TRANSACTION. */
public class TransactionalHttpCall {
    RestClient client;

    @Transactional
    void callWhileHoldingLocks() {
        client.get().uri("http://example.invalid").retrieve().toBodilessEntity();
    }
}
