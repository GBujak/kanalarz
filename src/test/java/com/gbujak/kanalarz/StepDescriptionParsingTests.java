package com.gbujak.kanalarz;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class StepDescriptionParsingTests {
    @Test
    void testNoPlaceholders() {
        String input = "Hello, world!";
        var result = ParameterizedStepDescription.parse(input);

        assertThat(result.parameterizedDescription()).isEqualTo("Hello, world!");
        assertThat(result.parameters()).isEmpty();
    }

    @Test
    void testSinglePlaceholder() {
        String input = "Hello, {name}!";
        var result = ParameterizedStepDescription.parse(input);

        assertThat(result.parameterizedDescription()).isEqualTo("Hello, {}!");
        assertThat(result.parameters()).containsExactly("name");
    }

    @Test
    void testMultiplePlaceholders() {
        String input = "{greeting}, {name}! How is {weather} today?";
        var result = ParameterizedStepDescription.parse(input);

        assertThat(result.parameterizedDescription()).isEqualTo("{}, {}! How is {} today?");
        assertThat(result.parameters()).containsExactly("greeting", "name", "weather");
    }

    @Test
    void testLiteralPlaceholders() {
        String input = "This is a literal: {{literal}}, and this is a placeholder: {placeholder}";
        var result = ParameterizedStepDescription.parse(input);

        assertThat(result.parameterizedDescription())
            .isEqualTo("This is a literal: {literal}, and this is a placeholder: {}");
        assertThat(result.parameters()).containsExactly("placeholder");
    }

    @Test
    void testMixedLiteralsAndPlaceholders() {
        String input = "{{literal1}}, {placeholder1}, {{literal2}}, {placeholder2}";
        var result = ParameterizedStepDescription.parse(input);

        assertThat(result.parameterizedDescription()).isEqualTo("{literal1}, {}, {literal2}, {}");
        assertThat(result.parameters()).containsExactly("placeholder1", "placeholder2");
    }

    @Test
    void testEmptyPlaceholder() {
        String input = "Hello, {}!";
        var result = ParameterizedStepDescription.parse(input);

        assertThat(result.parameterizedDescription()).isEqualTo("Hello, {}!");
        assertThat(result.parameters()).containsExactly("");
    }

    @Test
    void testEmptyLiteral() {
        String input = "Hello, {{}}!";
        var result = ParameterizedStepDescription.parse(input);

        assertThat(result.parameterizedDescription()).isEqualTo("Hello, {}!");
        assertThat(result.parameters()).isEmpty();
    }

    @Test
    void testNestedBraces() {
        String input = "{outer{inner}}";
        assertThatThrownBy(() -> ParameterizedStepDescription.parse(input))
            .isExactlyInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Nested placeholder braces");
    }

    @Test
    void testNestedBracesInLiteral() {
        String input = "{{outer {inner} {{test}} hello}}}}";
        var result = ParameterizedStepDescription.parse(input);

        assertThat(result.parameterizedDescription()).isEqualTo("{outer {inner} {{test} hello}}}}");
        assertThat(result.parameters()).isEmpty();
    }

    @Test
    void testUnclosedPlaceholder() {
        String input = "Hello, {unclosed";
        assertThatThrownBy(() -> ParameterizedStepDescription.parse(input))
            .isExactlyInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unclosed");
    }

    @Test
    void testUnclosedLiteral() {
        String input = "Hello, {{unclosed";
        assertThatThrownBy(() -> ParameterizedStepDescription.parse(input))
            .isExactlyInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unclosed");
    }

    @Test
    void testOnlyPlaceholders() {
        String input = "{placeholder1}{placeholder2}";
        var result = ParameterizedStepDescription.parse(input);

        assertThat(result.parameterizedDescription()).isEqualTo("{}{}");
        assertThat(result.parameters()).containsExactly("placeholder1", "placeholder2");
    }

    @Test
    void testPlaceholdersRepeating() {
        String input = "{placeholder1}{placeholder2}{placeholder1}{placeholder1}";
        var result = ParameterizedStepDescription.parse(input);

        assertThat(result.parameterizedDescription()).isEqualTo("{}{}{}{}");
        assertThat(result.parameters())
            .containsExactly("placeholder1", "placeholder2", "placeholder1", "placeholder1");
    }

    @Test
    void testOnlyLiterals() {
        String input = "{{literal1}}{{literal2}}";
        var result = ParameterizedStepDescription.parse(input);

        assertThat(result.parameterizedDescription()).isEqualTo("{literal1}{literal2}");
        assertThat(result.parameters()).isEmpty();
    }
}
