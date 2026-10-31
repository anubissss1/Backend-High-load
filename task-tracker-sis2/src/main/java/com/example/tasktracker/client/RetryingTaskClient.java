package com.example.tasktracker.client;

import com.example.tasktracker.proto.*;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** Each attempt has a deadline. Mutating retries require a stable idempotency key. */
public final class RetryingTaskClient implements AutoCloseable {
    private final ManagedChannel channel;
    private final String userId;

    public RetryingTaskClient(String host, int port, String userId) {
        this.channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        this.userId = userId;
    }

    public TaskReply create(CreateTaskRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Stable idempotency key is mandatory for retryable writes");
        }
        return attempt(() -> stub(idempotencyKey).createTask(request));
    }

    public TaskReply get(String id) {
        return attempt(() -> stub(null).getTask(GetTaskRequest.newBuilder().setId(id).build()));
    }

    private TaskServiceGrpc.TaskServiceBlockingStub stub(String key) {
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("x-user-id", Metadata.ASCII_STRING_MARSHALLER), userId);
        if (key != null) {
            metadata.put(Metadata.Key.of("idempotency-key", Metadata.ASCII_STRING_MARSHALLER), key);
        }
        return TaskServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                .withDeadlineAfter(250, TimeUnit.MILLISECONDS);
    }

    private TaskReply attempt(java.util.concurrent.Callable<TaskReply> action) {
        for (int i = 0; i < 3; i++) {
            try {
                return action.call();
            } catch (StatusRuntimeException exception) {
                Status.Code code = exception.getStatus().getCode();
                if (i == 2 || (code != Status.Code.UNAVAILABLE && code != Status.Code.DEADLINE_EXCEEDED)) {
                    throw exception;
                }
                try {
                    Thread.sleep((long) (25 * Math.pow(2, i)) + ThreadLocalRandom.current().nextLong(15));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw Status.CANCELLED.withCause(interrupted).asRuntimeException();
                }
            } catch (Exception exception) {
                throw new IllegalStateException("Client call failed", exception);
            }
        }
        throw new IllegalStateException("Unreachable");
    }

    @Override
    public void close() throws InterruptedException {
        channel.shutdown().awaitTermination(3, TimeUnit.SECONDS);
    }
}
