package com.drewdrew1.server;

import io.grpc.MethodDescriptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Provides a small UTF-8 JSON marshaller for manually bound gRPC methods. */
final class GrpcJson {
    static final MethodDescriptor.Marshaller<String> MARSHALLER = new MethodDescriptor.Marshaller<>() {
        @Override
        public InputStream stream(String value) {
            String safe = value == null ? "" : value;
            return new ByteArrayInputStream(safe.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String parse(InputStream stream) {
            try {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to parse gRPC JSON payload", e);
            }
        }
    };

    private GrpcJson() {
    }
}
