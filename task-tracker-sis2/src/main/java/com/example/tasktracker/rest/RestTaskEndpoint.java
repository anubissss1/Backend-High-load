package com.example.tasktracker.rest;

import com.example.tasktracker.core.TaskException;
import com.example.tasktracker.core.TaskInput;
import com.example.tasktracker.core.TaskStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

public final class RestTaskEndpoint implements HttpHandler {
    private final TaskStore store;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public RestTaskEndpoint(TaskStore store) {
        this.store = store;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String user = exchange.getRequestHeaders().getFirst("X-User-Id");
            if (user == null || user.isBlank()) {
                send(exchange, 401, Map.of("error", "X-User-Id required in demo"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.equals("/api/v1/tasks") && method.equals("POST")) {
                TaskInput input = mapper.readValue(exchange.getRequestBody(), TaskInput.class);
                send(exchange, 201, store.create(user, input,
                        exchange.getRequestHeaders().getFirst("Idempotency-Key")));
            } else if (path.equals("/api/v1/tasks") && method.equals("GET")) {
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                int limit = Integer.parseInt(query.getOrDefault("limit", "50"));
                TaskStore.Page page = store.list(user, query.get("status"), query.get("due_before"),
                        limit, query.get("cursor"));
                send(exchange, 200, Map.of("items", page.items(), "next_cursor", page.nextCursor()));
            } else if (path.startsWith("/api/v1/tasks/") && path.length() > "/api/v1/tasks/".length()) {
                String id = path.substring("/api/v1/tasks/".length());
                switch (method) {
                    case "GET" -> send(exchange, 200, store.get(user, id));
                    case "PUT" -> {
                        TaskInput input = mapper.readValue(exchange.getRequestBody(), TaskInput.class);
                        String version = exchange.getRequestHeaders().getFirst("If-Match");
                        long expected = version == null ? 0 : Long.parseLong(version);
                        send(exchange, 200, store.update(user, id, input, expected));
                    }
                    case "DELETE" -> {
                        store.delete(user, id);
                        exchange.sendResponseHeaders(204, -1);
                    }
                    default -> send(exchange, 405, Map.of("error", "Method not allowed"));
                }
            } else {
                send(exchange, 404, Map.of("error", "Unknown endpoint"));
            }
        } catch (TaskException exception) {
            int status = switch (exception.kind()) {
                case INVALID -> 400;
                case NOT_FOUND -> 404;
                case CONFLICT -> 409;
            };
            send(exchange, status, Map.of("error", exception.getMessage()));
        } catch (IllegalArgumentException exception) {
            send(exchange, 400, Map.of("error", "Invalid numeric parameter"));
        } catch (Exception exception) {
            send(exchange, 500, Map.of("error", "Internal server error"));
        } finally {
            exchange.close();
        }
    }

    private Map<String, String> parseQuery(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        return Arrays.stream(raw.split("&")).map(segment -> segment.split("=", 2))
                .collect(Collectors.toMap(parts -> decode(parts[0]),
                        parts -> parts.length == 2 ? decode(parts[1]) : "", (a, b) -> b));
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
