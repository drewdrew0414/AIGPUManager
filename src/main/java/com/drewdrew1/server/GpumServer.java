package com.drewdrew1.server;

import com.drewdrew1.cli.AppContext;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Runs the Java 21 virtual-thread-backed gpum gRPC server. */
public class GpumServer implements AutoCloseable {
    private final int port;
    private final AppContext context;
    private final StorageHealthProbe storageHealthProbe;
    private ExecutorService executor;
    private Server server;

    public GpumServer(int port, AppContext context, StorageHealthProbe storageHealthProbe) {
        this.port = port;
        this.context = context;
        this.storageHealthProbe = storageHealthProbe;
    }

    public void start() throws IOException {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server = NettyServerBuilder.forPort(port)
                .executor(executor)
                .addService(new GpumGrpcService(context, storageHealthProbe))
                .build()
                .start();
    }

    public int port() {
        return server == null ? port : server.getPort();
    }

    public void awaitTermination() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    @Override
    public void close() {
        if (server != null) {
            server.shutdown();
        }
        if (executor != null) {
            executor.shutdown();
        }
    }
}
