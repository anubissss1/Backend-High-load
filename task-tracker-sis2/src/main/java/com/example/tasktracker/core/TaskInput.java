package com.example.tasktracker.core;

public record TaskInput(String title, String description, String status, int priority, String dueAt) {
}
