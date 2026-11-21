package net.jnet.host;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.*;

final class SocksProxy implements Closeable {
    static final int PORT = 1080;
    private final ServerSocket server;
    private final ExecutorService workers = Executors.newFixedThreadPool(64);
    private final Semaphore capacity = new Semaphore(32);
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();

    SocksProxy(InetAddress address) throws IOException {
        server = new ServerSocket();
        try {
            server.bind(new InetSocketAddress(address, PORT));
        } catch (IOException e) {
            server.close();
            throw e;
        }
        new Thread(this::accept, "SOCKS listener").start();
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                if (!capacity.tryAcquire()) {
                    client.close();
                    continue;
                }
                sockets.add(client);
                try {
                    workers.execute(() -> serve(client));
                } catch (RejectedExecutionException e) {
                    sockets.remove(client);
                    client.close();
                    capacity.release();
                }
            } catch (IOException e) {
                break;
            }
        }
    }

    private void serve(Socket client) {
        try (client; Socket upstream = new Socket()) {
            sockets.add(upstream);
            try {
                if (server.isClosed()) return;
                client.setSoTimeout(10000);
                var input = new DataInputStream(client.getInputStream());
                var output = client.getOutputStream();
                if (input.readUnsignedByte() != 5) return;
                int count = input.readUnsignedByte();
                boolean noAuth = false;
                for (int i = 0; i < count; i++) noAuth |= input.readUnsignedByte() == 0;
                output.write(new byte[]{5, (byte) (noAuth ? 0 : 255)});
                if (!noAuth || input.readUnsignedByte() != 5) return;
                int command = input.readUnsignedByte();
                if (input.readUnsignedByte() != 0) return;
                if (command != 1) { reply(output, 7); return; }
                int type = input.readUnsignedByte();
                String host;
                switch (type) {
                    case 1: host = InetAddress.getByAddress(read(input, 4)).getHostAddress(); break;
                    case 4: host = InetAddress.getByAddress(read(input, 16)).getHostAddress(); break;
                    case 3: host = new String(read(input, input.readUnsignedByte()), StandardCharsets.US_ASCII); break;
                    default: reply(output, 8); return;
                }
                int port = input.readUnsignedShort();
                if (host.isBlank() || port == 0) { reply(output, 1); return; }
                try {
                    upstream.connect(new InetSocketAddress(host, port), 10000);
                } catch (IOException e) {
                    reply(output, e instanceof ConnectException ? 5 : 4);
                    return;
                }
                reply(output, 0);
                client.setSoTimeout(0);
                FutureTask<Void> send = new FutureTask<>(() -> { pipe(client, upstream); return null; });
                new Thread(send, "SOCKS upstream").start();
                pipe(upstream, client);
                send.get();
            } finally {
                sockets.remove(upstream);
            }
        } catch (IOException | ExecutionException ignored) {
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            sockets.remove(client);
            capacity.release();
        }
    }

    private static byte[] read(DataInputStream input, int count) throws IOException {
        byte[] bytes = new byte[count];
        input.readFully(bytes);
        return bytes;
    }
    private static void reply(OutputStream output, int status) throws IOException {
        output.write(new byte[]{5, (byte) status, 0, 1, 0, 0, 0, 0, 0, 0});
    }
    private static void pipe(Socket source, Socket destination) {
        try {
            source.getInputStream().transferTo(destination.getOutputStream());
            destination.shutdownOutput();
        } catch (IOException e) {
            quietClose(source);
            quietClose(destination);
        }
    }
    private static void quietClose(Closeable item) {
        try { item.close(); } catch (IOException ignored) {}
    }
    @Override public void close() {
        quietClose(server);
        for (Socket socket : sockets) quietClose(socket);
        workers.shutdown();
    }
}
