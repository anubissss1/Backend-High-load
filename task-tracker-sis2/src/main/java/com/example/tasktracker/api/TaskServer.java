package com.example.tasktracker.api;

import com.example.tasktracker.core.TaskStore;
import com.example.tasktracker.grpc.GrpcTaskEndpoint;
import com.example.tasktracker.rest.RestTaskEndpoint;
import com.sun.net.httpserver.HttpServer;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public final class TaskServer {
    private TaskServer() { }

    public static void main(String[] args) throws Exception {
        TaskStore store = new TaskStore();
        Server grpc = ServerBuilder.forPort(9090)
                .addService(ServerInterceptors.intercept(new GrpcTaskEndpoint(store),
                        GrpcTaskEndpoint.metadataInterceptor()))
                .build().start();
        HttpServer rest = HttpServer.create(new InetSocketAddress(8080), 0);
        rest.createContext("/api/v1/tasks", new RestTaskEndpoint(store));
        rest.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        rest.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            grpc.shutdown();
            rest.stop(0);
        }));
        System.out.println("gRPC :9090 | REST http://localhost:8080/api/v1/tasks");
        grpc.awaitTermination();
    }
}
