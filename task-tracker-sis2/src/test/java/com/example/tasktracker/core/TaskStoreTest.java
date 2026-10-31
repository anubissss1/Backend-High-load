package com.example.tasktracker.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

final class TaskStoreTest {
    private static TaskInput input(String title) {
        return new TaskInput(title, "Details", "TODO", 2, "2026-12-01T12:00:00Z");
    }

    @Test
    void duplicateCreationReturnsSameTask() {
        TaskStore store = new TaskStore();
        Task first = store.create("alice", input("Pay bills"), "key-1");
        Task second = store.create("alice", input("Pay bills"), "key-1");
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(store.list("alice", "", "", 50, "").items()).hasSize(1);
    }

    @Test
    void keyReusedWithOtherPayloadIsRejected() {
        TaskStore store = new TaskStore();
        store.create("alice", input("A"), "key-1");
        assertThatThrownBy(() -> store.create("alice", input("B"), "key-1"))
                .isInstanceOf(TaskException.class)
                .hasMessageContaining("different content");
    }

    @Test
    void versionConflictAndOwnerIsolation() {
        TaskStore store = new TaskStore();
        Task task = store.create("alice", input("A"), "key-1");
        assertThatThrownBy(() -> store.get("bob", task.id())).isInstanceOf(TaskException.class);
        Task updated = store.update("alice", task.id(), input("Updated"), 1);
        assertThat(updated.version()).isEqualTo(2);
        assertThatThrownBy(() -> store.update("alice", task.id(), input("Again"), 1))
                .isInstanceOf(TaskException.class).hasMessageContaining("Version mismatch");
    }

    @Test
    void filtersAndPaginationAreTenantBounded() {
        TaskStore store = new TaskStore();
        store.create("alice", input("A"), "k1");
        store.create("alice", input("B"), "k2");
        store.create("bob", input("C"), "k3");
        TaskStore.Page first = store.list("alice", "TODO", "2027-01-01T00:00:00Z", 1, "");
        assertThat(first.items()).hasSize(1);
        assertThat(first.nextCursor()).isNotBlank();
        TaskStore.Page second = store.list("alice", "TODO", "", 1, first.nextCursor());
        assertThat(second.items()).hasSize(1);
        assertThat(second.nextCursor()).isBlank();
    }
}
