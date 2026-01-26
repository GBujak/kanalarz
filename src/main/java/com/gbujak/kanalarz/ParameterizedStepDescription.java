package com.gbujak.kanalarz;

import org.jspecify.annotations.NullMarked;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@NullMarked
public class ParameterizedStepDescription {
    private final String parameterizedDescription;
    private final List<String> parameters;

    private ParameterizedStepDescription(
        String parameterizedMessage,
        List<String> parameters
    ) {
        this.parameterizedDescription = parameterizedMessage;
        this.parameters = parameters;
    }

    public String parameterizedDescription() {
        return parameterizedDescription;
    }

    public List<String> parameters() {
        return parameters;
    }

    public static ParameterizedStepDescription parse(String message) {
        Objects.requireNonNull(message);
        List<String> extractedValues = new ArrayList<>();
        StringBuilder modifiedString = new StringBuilder(message.length());
        int i = 0;
        int n = message.length();

        while (i < n) {
            if (message.charAt(i) == '{') {
                if (i + 1 < n && message.charAt(i + 1) == '{') {
                    // Handle escaped braces: {{ ... }}
                    i += 2;
                    StringBuilder literal = new StringBuilder(16);
                    while (i < n && !(message.charAt(i) == '}' && i + 1 < n && message.charAt(i + 1) == '}')) {
                        literal.append(message.charAt(i));
                        i++;
                    }
                    if (i >= n) {
                        throw new IllegalArgumentException(
                            "Unclosed escaped brace at position: " + (i - literal.length() - 2)
                        );
                    }
                    modifiedString.append("{").append(literal).append("}");
                    i += 2;
                } else {
                    // Handle regular braces: { ... }
                    i++;
                    StringBuilder value = new StringBuilder(16);
                    boolean closed = false;
                    while (i < n && message.charAt(i) != '}') {
                        if (message.charAt(i) == '{') {
                            throw new IllegalArgumentException(
                                "Nested placeholder braces are not allowed at position: " + i
                            );
                        }
                        value.append(message.charAt(i));
                        i++;
                    }
                    if (i < n && message.charAt(i) == '}') {
                        closed = true;
                        i++;
                    }
                    if (!closed) {
                        throw new IllegalArgumentException(
                            "Unclosed brace at position: " + (i - value.length() - 1)
                        );
                    }
                    extractedValues.add(value.toString());
                    modifiedString.append("{}");
                }
            } else {
                modifiedString.append(message.charAt(i));
                i++;
            }
        }
        return new ParameterizedStepDescription(
            modifiedString.toString(),
            Collections.unmodifiableList(extractedValues)
        );
    }

    @Override
    public String toString() {
        return "ParameterizedStepDescription{" +
            "parameterizedDescription='" + parameterizedDescription + '\'' +
            ", parameters=" + parameters +
            '}';
    }
}
