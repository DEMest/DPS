package org.example;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Ответ сервера на один URL:
 * <pre>
 *   {"message": "some text", "successors": ["path_1", "path_2", …]}
 * </pre>
 * Лишние поля пропускаются, отсутствующий {@code successors} считается пустым списком.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Page(String message, List<String> successors) {

    public Page {
        successors = successors == null ? List.of() : List.copyOf(successors);
    }
}
