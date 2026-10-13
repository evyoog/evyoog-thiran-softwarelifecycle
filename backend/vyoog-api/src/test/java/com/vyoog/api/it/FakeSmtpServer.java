package com.vyoog.api.it;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * VYB-0955: a minimal SMTP server for the notification tests, listening on this machine only: it accepts a message and keeps it. It
 * speaks just enough SMTP (EHLO, MAIL, RCPT, DATA, RSET, NOOP, QUIT), advertises no STARTTLS and no AUTH, and can be told to refuse
 * every message so the retry path can be seen. It stands in for the real mail server, which no test here can reach.
 */
final class FakeSmtpServer implements AutoCloseable {

    record Message(String from, List<String> to, String data) {
        String subject() {
            for (String line : data.split("\r?\n")) {
                if (line.isEmpty()) break;
                if (line.regionMatches(true, 0, "Subject:", 0, 8)) return line.substring(8).strip();
            }
            return "";
        }
        String body() {
            int i = data.indexOf("\r\n\r\n");
            if (i < 0) i = data.indexOf("\n\n");
            return i < 0 ? "" : data.substring(i).strip();
        }
    }

    private final ServerSocket server;
    private final List<Message> received = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean refusing = new AtomicBoolean(false);
    private final Thread acceptor;
    private volatile boolean closed;

    FakeSmtpServer() {
        try {
            server = new ServerSocket(0, 20, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        acceptor = new Thread(this::acceptLoop, "fake-smtp");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int port() { return server.getLocalPort(); }

    List<Message> received() { synchronized (received) { return new ArrayList<>(received); } }

    void refuseEverything(boolean refuse) { refusing.set(refuse); }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket s = server.accept();
                Thread t = new Thread(() -> serve(s), "fake-smtp-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket s) {
        try (s; BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             Writer out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8)) {
            reply(out, "220 fake-smtp ready");
            String from = null;
            List<String> to = new ArrayList<>();
            String line;
            while ((line = in.readLine()) != null) {
                String u = line.toUpperCase();
                if (u.startsWith("EHLO")) {
                    reply(out, "250-fake-smtp");
                    reply(out, "250 8BITMIME");
                } else if (u.startsWith("HELO")) {
                    reply(out, "250 fake-smtp");
                } else if (u.startsWith("MAIL FROM:")) {
                    from = angle(line);
                    to = new ArrayList<>();
                    reply(out, "250 ok");
                } else if (u.startsWith("RCPT TO:")) {
                    to.add(angle(line));
                    reply(out, "250 ok");
                } else if (u.equals("DATA")) {
                    reply(out, "354 go ahead");
                    StringBuilder data = new StringBuilder();
                    while ((line = in.readLine()) != null && !line.equals(".")) {
                        data.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n");
                    }
                    if (refusing.get()) {
                        reply(out, "554 refused for the test");
                    } else {
                        received.add(new Message(from, to, data.toString()));
                        reply(out, "250 queued");
                    }
                } else if (u.equals("RSET") || u.equals("NOOP")) {
                    reply(out, "250 ok");
                } else if (u.equals("QUIT")) {
                    reply(out, "221 bye");
                    return;
                } else {
                    reply(out, "502 not supported");
                }
            }
        } catch (IOException ignored) {
            // a client that hangs up is not an error for a test server
        }
    }

    private static String angle(String line) {
        int a = line.indexOf('<');
        int b = line.indexOf('>');
        return a >= 0 && b > a ? line.substring(a + 1, b) : line.substring(line.indexOf(':') + 1).strip();
    }

    private static void reply(Writer out, String text) throws IOException {
        out.write(text + "\r\n");
        out.flush();
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
            // closing a test server
        }
    }
}
