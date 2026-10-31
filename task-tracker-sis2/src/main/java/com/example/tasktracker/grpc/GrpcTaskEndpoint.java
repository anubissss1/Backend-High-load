package com.example.tasktracker.grpc;

import com.example.tasktracker.core.Task;
import com.example.tasktracker.core.TaskException;
import com.example.tasktracker.core.TaskInput;
import com.example.tasktracker.core.TaskStore;
import com.example.tasktracker.proto.*;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import java.util.function.Supplier;

public final class GrpcTaskEndpoint extends TaskServiceGrpc.TaskServiceImplBase {
    private static final Context.Key<String> USER = Context.key("user");
    private static final Context.Key<String> KEY = Context.key("key");
    private final TaskStore store;

    public GrpcTaskEndpoint(TaskStore store) {
        this.store = store;
    }

    public static ServerInterceptor metadataInterceptor() {
        return (call, headers, next) -> {
            String user = headers.get(Metadata.Key.of("x-user-id", Metadata.ASCII_STRING_MARSHALLER));
            String key = headers.get(Metadata.Key.of("idempotency-key", Metadata.ASCII_STRING_MARSHALLER));
            if (user == null || user.isBlank()) {
                call.close(Status.UNAUTHENTICATED.withDescription("x-user-id required in demo"), new Metadata());
                return new ServerCall.Listener<>() { };
            }
            return Contexts.interceptCall(Context.current().withValue(USER, user).withValue(KEY, key), call, headers, next);
        };
    }

    @Override
    public void createTask(CreateTaskRequest request, StreamObserver<TaskReply> observer) {
        respond(observer, () -> reply(store.create(USER.get(), new TaskInput(request.getTitle(), request.getDescription(),
                request.getStatus(), request.getPriority(), request.getDueAt()), KEY.get())));
    }

    @Override
    public void getTask(GetTaskRequest request, StreamObserver<TaskReply> observer) {
        respond(observer, () -> reply(store.get(USER.get(), request.getId())));
    }

    @Override
    public void listTasks(ListTasksRequest request, StreamObserver<ListTasksReply> observer) {
        respond(observer, () -> {
            TaskStore.Page page = store.list(USER.get(), request.getStatus(), request.getDueBefore(),
                    request.getLimit(), request.getCursor());
            ListTasksReply.Builder builder = ListTasksReply.newBuilder().setNextCursor(page.nextCursor());
            page.items().forEach(task -> builder.addItems(toProto(task)));
            return builder.build();
        });
    }

    @Override
    public void updateTask(UpdateTaskRequest request, StreamObserver<TaskReply> observer) {
        respond(observer, () -> reply(store.update(USER.get(), request.getId(),
                new TaskInput(request.getTitle(), request.getDescription(), request.getStatus(),
                        request.getPriority(), request.getDueAt()), request.getExpectedVersion())));
    }

    @Override
    public void deleteTask(DeleteTaskRequest request, StreamObserver<DeleteTaskReply> observer) {
        respond(observer, () -> {
            store.delete(USER.get(), request.getId());
            return DeleteTaskReply.newBuilder().setDeleted(true).build();
        });
    }

    private static TaskReply reply(Task task) {
        return TaskReply.newBuilder().setTask(toProto(task)).build();
    }

    private static com.example.tasktracker.proto.Task toProto(Task task) {
        return com.example.tasktracker.proto.Task.newBuilder()
                .setId(task.id()).setUserId(task.userId()).setTitle(task.title())
                .setDescription(task.description()).setStatus(task.status())
                .setPriority(task.priority()).setDueAt(task.dueAt())
                .setCreatedAt(task.createdAt().toString()).setUpdatedAt(task.updatedAt().toString())
                .setVersion(task.version()).build();
    }

    private static <T> void respond(StreamObserver<T> observer, Supplier<T> action) {
        try {
            observer.onNext(action.get());
            observer.onCompleted();
        } catch (TaskException exception) {
            Status status = switch (exception.kind()) {
                case INVALID -> Status.INVALID_ARGUMENT;
                case NOT_FOUND -> Status.NOT_FOUND;
                case CONFLICT -> Status.ALREADY_EXISTS;
            };
            observer.onError(status.withDescription(exception.getMessage()).asRuntimeException());
        } catch (RuntimeException exception) {
            observer.onError(Status.INTERNAL.withDescription("Unexpected failure").asRuntimeException());
        }
    }
}
