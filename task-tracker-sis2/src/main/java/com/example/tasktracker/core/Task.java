package com.example.tasktracker.core;

import java.time.Instant;

public record Task(
        String id, String userId, String title, String description, String status,
        int priority, String dueAt, Instant createdAt, Instant updatedAt, long version) {
}
