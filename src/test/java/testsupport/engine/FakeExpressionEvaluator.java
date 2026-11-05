package testsupport.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import com.crisil.apiorch.expression.CompiledExpression;
import com.crisil.apiorch.expression.ExpressionEvaluator;
import com.crisil.apiorch.expression.ExpressionException;

/**
 * An {@link ExpressionEvaluator} for compiler and engine tests that do not exercise JSONata. Anything compiles,
 * except text containing {@code !syntax} (a syntax error) or a regex literal {@code /…/} (not allowed);
 * evaluation returns {@link MissingNode}. The real engine is {@code GuardedJsonataEvaluator} (ADR-002).
 */
public final class FakeExpressionEvaluator implements ExpressionEvaluator {

    record Compiled(String source) implements CompiledExpression {
    }

    @Override
    public CompiledExpression compile(String source) {
        if (source.contains("!syntax")) {
            throw new ExpressionException(ExpressionException.Kind.SYNTAX, "S0201", source.indexOf("!syntax"),
                    "Syntax error", null);
        }
        if (source.matches(".*\\$\\w+\\([^)]*/[^/]+/.*")) {
            throw new ExpressionException(ExpressionException.Kind.NOT_ALLOWED, "REGEX_NOT_ALLOWED", -1,
                    "Regex literals are not allowed in R1", null);
        }
        return new Compiled(source);
    }

    @Override
    public JsonNode evaluate(CompiledExpression expression, JsonNode context) {
        return MissingNode.getInstance();
    }
}
