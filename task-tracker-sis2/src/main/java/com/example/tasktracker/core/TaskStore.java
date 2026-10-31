package com.example.tasktracker.core;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.example.tasktracker.core.TaskException.Kind.*;

/** Demo persistence: thread-safe, process-local and deliberately non-durable. */
public final class TaskStore {
    public record Page(List<Task> items, String nextCursor) { }
    private record IdempotencyEntry(String fingerprint, Task result) { }
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final Map<String, IdempotencyEntry> idempotency = new ConcurrentHashMap<>();

    public Task create(String userId, TaskInput input, String idempotencyKey) {
        validate(input);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new TaskException(INVALID, "Idempotency-Key is required for task creation");
        }
        String scope = userId + ":" + idempotencyKey;
        String fingerprint = input.toString();
        synchronized (idempotency) {
            IdempotencyEntry existing = idempotency.get(scope);
            if (existing != null) {
                if (!existing.fingerprint().equals(fingerprint)) {
                    throw new TaskException(CONFLICT, "Idempotency key reused with different content");
                }
                return existing.result();
            }
            Instant now = Instant.now();
            Task task = new Task(UUID.randomUUID().toString(), userId, input.title().trim(),
                    blank(input.description()), normalizeStatus(input.status()), input.priority(),
                    blank(input.dueAt()), now, now, 1);
            tasks.put(task.id(), task);
            idempotency.put(scope, new IdempotencyEntry(fingerprint, task));
            return task;
        }
    }

    public Task get(String userId, String id) {
        Task task = tasks.get(id);
        if (task == null || !task.userId().equals(userId)) {
            throw new TaskException(NOT_FOUND, "Task not found");
        }
        return task;
    }

    public Page list(String userId, String status, String dueBefore, int requestedLimit, String cursor) {
        int limit = requestedLimit == 0 ? 50 : requestedLimit;
        if (limit < 1 || limit > 100) {
            throw new TaskException(INVALID, "limit must be between 1 and 100");
        }
        String normalized = status == null || status.isBlank() ? "" : normalizeStatus(status);
        Instant deadline = parseOptionalTime(dueBefore);
        List<Task> filtered = tasks.values().stream()
                .filter(task -> task.userId().equals(userId))
                .filter(task -> normalized.isEmpty() || task.status().equals(normalized))
                .filter(task -> deadline == null || (!task.dueAt().isBlank()
                        && Instant.parse(task.dueAt()).isBefore(deadline)))
                .sorted(Comparator.comparing(Task::id))
                .filter(task -> cursor == null || cursor.isBlank() || task.id().compareTo(cursor) > 0)
                .limit(limit + 1L)
                .toList();
        boolean hasMore = filtered.size() > limit;
        List<Task> page = new ArrayList<>(filtered.subList(0, Math.min(filtered.size(), limit)));
        return new Page(page, hasMore ? page.getLast().id() : "");
    }

    public Task update(String userId, String id, TaskInput input, long expectedVersion) {
        validate(input);
        if (expectedVersion <= 0) {
            throw new TaskException(INVALID, "expected_version must be positive");
        }
        return tasks.compute(id, (key, current) -> {
            if (current == null || !current.userId().equals(userId)) {
                throw new TaskException(NOT_FOUND, "Task not found");
            }
            if (current.version() != expectedVersion) {
                throw new TaskException(CONFLICT, "Version mismatch");
            }
            return new Task(id, userId, input.title().trim(), blank(input.description()),
                    normalizeStatus(input.status()), input.priority(), blank(input.dueAt()),
                    current.createdAt(), Instant.now(), current.version() + 1);
        });
    }

    public void delete(String userId, String id) {
        tasks.compute(id, (key, current) -> {
            if (current == null || !current.userId().equals(userId)) {
                throw new TaskException(NOT_FOUND, "Task not found");
            }
            return null;
        });
    }

    private static void validate(TaskInput input) {
        if (input == null || input.title() == null || input.title().isBlank()
                || input.title().length() > 255) {
            throw new TaskException(INVALID, "title must be 1..255 characters");
        }
        normalizeStatus(input.status());
        if (input.priority() < 0 || input.priority() > 5) {
            throw new TaskException(INVALID, "priority must be 0..5");
        }
        parseOptionalTime(input.dueAt());
    }

    private static String normalizeStatus(String value) {
        String status = value == null || value.isBlank() ? "TODO" : value;
        if (!List.of("TODO", "IN_PROGRESS", "DONE").contains(status)) {
            throw new TaskException(INVALID, "Unknown status");
        }
        return status;
    }

    private static Instant parseOptionalTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            throw new TaskException(INVALID, "Use ISO-8601 UTC time for due_at/due_before");
        }
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }
}
