package org.example;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class Spider {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Page(String message, List<String> successors) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<String> visited = ConcurrentHashMap.newKeySet();
    private final Map<String, String> messages = new ConcurrentHashMap<>();
    private final Set<String> failed = ConcurrentHashMap.newKeySet();

    public Spider(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public List<String> crawl() throws Exception {
        visited.add("/");
        try (executor) {
            executor.submit(() -> visit("/")).get();
        }
        List<String> result = new ArrayList<>(messages.values());
        Collections.sort(result);
        return result;
    }

    public Set<String> getFailed() {
        return failed;
    }

    private Void visit(String path) throws Exception {
        Page page;
        try {
            page = fetch(path);
        } catch (IOException e) {
            System.err.println("Не удалось получить " + path + ": " + e);
            failed.add(path);
            return null;
        }
        messages.put(path, page.message());

        List<Future<Void>> children = new ArrayList<>();
        for (String successor : page.successors()) {
            String next = successor.startsWith("/") ? successor : "/" + successor;
            if (visited.add(next)) {
                children.add(executor.submit(() -> visit(next)));
            }
        }
        for (Future<Void> child : children) {
            child.get();
        }
        return null;
    }

    private Page fetch(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .build();
        IOException error = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IOException("код ответа " + response.statusCode());
                }
                return JSON.readValue(response.body(), Page.class);
            } catch (IOException e) {
                error = e;
            }
        }
        throw error;
    }
}
