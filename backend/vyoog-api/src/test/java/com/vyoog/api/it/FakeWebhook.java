package com.vyoog.api.it;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** VYB-0955: a chat webhook for the notification tests, on this machine only: it keeps each post and can be told to answer with an error. */
final class FakeWebhook implements AutoCloseable {

    private final HttpServer server;
    private final List<String> posts = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger status = new AtomicInteger(202);

    FakeWebhook() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/hook/secret-signature", exchange -> {
            posts.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = status.get() / 100 == 2 ? new byte[0] : "rejected for the test".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), reply.length == 0 ? -1 : reply.length);
            if (reply.length > 0) exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
    }

    /** The address the application is configured with; its path stands in for the signature a real webhook carries. */
    String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook/secret-signature"; }

    List<String> posts() { synchronized (posts) { return new ArrayList<>(posts); } }

    void answerWith(int httpStatus) { status.set(httpStatus); }

    @Override
    public void close() { server.stop(0); }
}
