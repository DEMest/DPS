package org.example.common;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Разбор опций командной строки вида {@code --name value}, {@code --name=value} и {@code --flag}.
 * Значения, не заданные в командной строке, ищутся в конфигурационном файле (properties).
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Args {

    private final Map<String, String> options = new LinkedHashMap<>();
    private final List<String> positional = new ArrayList<>();
    private final Properties config = new Properties();

    /**
     * @param argv     аргументы командной строки
     * @param flagKeys имена опций, не требующих значения
     */
    public static Args parse(String[] argv, Set<String> flagKeys) {
        Args args = new Args();
        for (int i = 0; i < argv.length; i++) {
            String token = argv[i];
            if (!token.startsWith("--")) {
                args.positional.add(token);
                continue;
            }
            String key = token.substring(2);
            String value;
            int eq = key.indexOf('=');
            if (eq >= 0) {
                value = key.substring(eq + 1);
                key = key.substring(0, eq);
            } else if (flagKeys.contains(key)) {
                value = "true";
            } else {
                if (i + 1 >= argv.length) {
                    throw new IllegalArgumentException("Опция --" + key + " требует значения");
                }
                value = argv[++i];
            }
            args.options.put(key, value);
        }
        return args;
    }

    /** Загружает значения по умолчанию из properties-файла. */
    public void loadConfig(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            config.load(reader);
        } catch (IOException e) {
            throw new IllegalArgumentException("Не удалось прочитать конфигурационный файл " + file + ": " + e.getMessage(), e);
        }
    }

    public boolean has(String key) {
        return options.containsKey(key) || config.containsKey(key);
    }

    public List<String> positional() {
        return List.copyOf(positional);
    }

    public String get(String key, String defaultValue) {
        String value = options.get(key);
        if (value == null) {
            value = config.getProperty(key);
        }
        return value == null ? defaultValue : value;
    }

    public String require(String key, String description) {
        String value = get(key, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Не задана обязательная опция --" + key + " (" + description + ")");
        }
        return value;
    }

    public int getInt(String key, int defaultValue) {
        String value = get(key, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Опция --" + key + " должна быть целым числом, получено: " + value);
        }
    }

    public double getDouble(String key, double defaultValue) {
        String value = get(key, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Опция --" + key + " должна быть числом, получено: " + value);
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String value = get(key, null);
        return value == null ? defaultValue : Boolean.parseBoolean(value.trim());
    }
}
