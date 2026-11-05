package com.crisil.apiorch.definition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.crisil.apiorch.definition.PathTemplate.Part;

class PathTemplateTest {

    @Test
    void literalAndExpressionSegments() {
        PathTemplate t = PathTemplate.parse("/scores/{steps.api1.body.id}/v{ input.v }-x");
        assertThat(t.segments()).containsExactly(
                List.of(new Part("scores", false)),
                List.of(new Part("steps.api1.body.id", true)),
                List.of(new Part("v", false), new Part("input.v", true), new Part("-x", false)));
        assertThat(t.expressions()).containsExactly("steps.api1.body.id", "input.v");
    }

    @Test
    void bracesInsideAnExpressionAreMatched() {
        PathTemplate t = PathTemplate.parse("/a/{ {'k': '}'}.k }");
        assertThat(t.expressions()).containsExactly("{'k': '}'}.k");
    }

    @Test
    void rootAndRepeatedSlashes() {
        assertThat(PathTemplate.parse("/").segments()).isEmpty();
        assertThat(PathTemplate.parse("/a//b/").segments()).hasSize(2);
    }

    @Test
    void malformedPathsAreRejected() {
        assertThatThrownBy(() -> PathTemplate.parse("a")).hasMessageContaining("start with /");
        assertThatThrownBy(() -> PathTemplate.parse(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PathTemplate.parse("/a/{x")).hasMessageContaining("unclosed");
        assertThatThrownBy(() -> PathTemplate.parse("/a/x}")).hasMessageContaining("unmatched");
        assertThatThrownBy(() -> PathTemplate.parse("/a/{ }")).hasMessageContaining("empty");
    }
}
