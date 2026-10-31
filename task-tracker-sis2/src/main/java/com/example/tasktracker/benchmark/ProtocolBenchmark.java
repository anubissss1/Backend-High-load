package com.example.tasktracker.benchmark;

import com.example.tasktracker.proto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Runs comparable GETs against the SAME stored task, sequentially, with warmup. */
public final class ProtocolBenchmark {
    private ProtocolBenchmark() { }

    private record Result(String protocol, long p50Micros, long p95Micros,
                          long p99Micros, double avgPayloadBytes, int samples) { }

    public static void main(String[] args) throws Exception {
        int measured = args.length > 0 ? Integer.parseInt(args[0]) : 1000;
        int warmup = args.length > 1 ? Integer.parseInt(args[1]) : 200;
        if (measured < 1 || warmup < 0) {
            throw new IllegalArgumentException("samples > 0 and warmup >= 0 required");
        }
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        HttpClient http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build();
        ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", 9090).usePlaintext().build();
        try {
            Metadata metadata = new Metadata();
            metadata.put(Metadata.Key.of("x-user-id", Metadata.ASCII_STRING_MARSHALLER), "benchmark-user");
            metadata.put(Metadata.Key.of("idempotency-key", Metadata.ASCII_STRING_MARSHALLER), UUID.randomUUID().toString());
            TaskServiceGrpc.TaskServiceBlockingStub stub = TaskServiceGrpc.newBlockingStub(channel)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
            TaskReply created = stub.withDeadlineAfter(2, TimeUnit.SECONDS).createTask(
                    CreateTaskRequest.newBuilder().setTitle("Benchmark Task").setDescription("identical data")
                            .setStatus("TODO").setPriority(2).build());
            String id = created.getTask().getId();
            GetTaskRequest getRequest = GetTaskRequest.newBuilder().setId(id).build();
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080/api/v1/tasks/" + id))
                    .header("X-User-Id", "benchmark-user").timeout(java.time.Duration.ofSeconds(2))
                    .GET().build();

            for (int i = 0; i < warmup; i++) {
                stub.withDeadlineAfter(2, TimeUnit.SECONDS).getTask(getRequest);
                http.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            }
            Result grpc = measureGrpc(stub, getRequest, measured);
            Result rest = measureRest(http, httpRequest, measured);
            Files.createDirectories(Path.of("benchmark-results"));
            String report = "protocol,samples,p50_us,p95_us,p99_us,avg_payload_bytes\n"
                    + csv(grpc) + csv(rest);
            Files.writeString(Path.of("benchmark-results", "latest.csv"), report);
            System.out.print(report);
            System.out.println("Payload sizes are protobuf message bytes vs REST JSON body bytes; excludes HTTP/2 framing and headers.");
            System.out.println("Latency includes localhost transport; sequential GET, not 2000-RPS load testing.");
        } finally {
            channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    private static Result measureGrpc(TaskServiceGrpc.TaskServiceBlockingStub stub,
                                      GetTaskRequest request, int samples) {
        long[] times = new long[samples];
        long bytes = 0;
        for (int i = 0; i < samples; i++) {
            long start = System.nanoTime();
            TaskReply response = stub.withDeadlineAfter(2, TimeUnit.SECONDS).getTask(request);
            times[i] = System.nanoTime() - start;
            bytes += response.getSerializedSize();
        }
        return summarize("gRPC/Protobuf", times, bytes);
    }

    private static Result measureRest(HttpClient client, HttpRequest request, int samples) throws Exception {
        long[] times = new long[samples];
        long bytes = 0;
        for (int i = 0; i < samples; i++) {
            long start = System.nanoTime();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            times[i] = System.nanoTime() - start;
            if (response.statusCode() != 200) {
                throw new IllegalStateException("REST status " + response.statusCode());
            }
            bytes += response.body().length;
        }
        return summarize("REST/JSON", times, bytes);
    }

    private static Result summarize(String protocol, long[] nanos, long totalBytes) {
        Arrays.sort(nanos);
        return new Result(protocol, percentile(nanos, 0.50), percentile(nanos, 0.95),
                percentile(nanos, 0.99), totalBytes / (double) nanos.length, nanos.length);
    }

    private static long percentile(long[] values, double fraction) {
        int index = Math.max(0, (int) Math.ceil(values.length * fraction) - 1);
        return Math.round(values[index] / 1000.0);
    }

    private static String csv(Result value) {
        return "%s,%d,%d,%d,%d,%.1f%n".formatted(value.protocol(), value.samples(),
                value.p50Micros(), value.p95Micros(), value.p99Micros(), value.avgPayloadBytes());
    }
}
