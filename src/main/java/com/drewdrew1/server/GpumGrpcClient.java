package com.drewdrew1.server;

import io.grpc.LoadBalancerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.internal.PickFirstLoadBalancerProvider;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.StreamObserver;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Minimal gRPC client for gpum server health, allocation and execution endpoints. */
public class GpumGrpcClient implements AutoCloseable {
    private final ManagedChannel channel;

    static {
        LoadBalancerRegistry.getDefaultRegistry().register(new PickFirstLoadBalancerProvider());
    }

    public GpumGrpcClient(String host, int port) {
        this.channel = NettyChannelBuilder.forAddress(new InetSocketAddress(host, port)).usePlaintext().build();
    }

    public String health() {
        return ClientCalls.blockingUnaryCall(channel, GpumGrpcService.HEALTH, io.grpc.CallOptions.DEFAULT, "{}");
    }

    public String listResources() {
        return ClientCalls.blockingUnaryCall(channel, GpumGrpcService.LIST_RESOURCES, io.grpc.CallOptions.DEFAULT, "{}");
    }

    public String planAllocation(String json) {
        return ClientCalls.blockingUnaryCall(channel, GpumGrpcService.PLAN_ALLOCATION, io.grpc.CallOptions.DEFAULT, json);
    }

    public String allocate(String json) {
        return ClientCalls.blockingUnaryCall(channel, GpumGrpcService.ALLOCATE, io.grpc.CallOptions.DEFAULT, json);
    }

    public String release(String json) {
        return ClientCalls.blockingUnaryCall(channel, GpumGrpcService.RELEASE, io.grpc.CallOptions.DEFAULT, json);
    }

    public String submitJob(String json) {
        return ClientCalls.blockingUnaryCall(channel, GpumGrpcService.SUBMIT_JOB, io.grpc.CallOptions.DEFAULT, json);
    }

    public String nodeHeartbeat(String json) {
        return ClientCalls.blockingUnaryCall(channel, GpumGrpcService.NODE_HEARTBEAT, io.grpc.CallOptions.DEFAULT, json);
    }

    public String telemetryOnce(String filter) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> response = new AtomicReference<>("");
        StreamObserver<String> request = ClientCalls.asyncBidiStreamingCall(
                channel.newCall(GpumGrpcService.STREAM_TELEMETRY, io.grpc.CallOptions.DEFAULT),
                new StreamObserver<>() {
                    @Override public void onNext(String value) {
                        response.set(value);
                        latch.countDown();
                    }
                    @Override public void onError(Throwable throwable) {
                        response.set("{\"error\":\"" + throwable.getMessage() + "\"}");
                        latch.countDown();
                    }
                    @Override public void onCompleted() {
                        latch.countDown();
                    }
                });
        request.onNext("{\"clientId\":\"cli\",\"filter\":\"" + (filter == null ? "" : filter) + "\"}");
        request.onCompleted();
        latch.await(10, TimeUnit.SECONDS);
        return response.get();
    }

    @Override
    public void close() throws Exception {
        channel.shutdownNow();
        channel.awaitTermination(5, TimeUnit.SECONDS);
    }
}
