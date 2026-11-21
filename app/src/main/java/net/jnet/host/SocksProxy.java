package net.jnet.host;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.*;

final class SocksProxy implements Closeable {
    static final int PORT = 1080;
    private final ServerSocket server;
    private final boolean udpEnabled;
    private final ExecutorService workers = Executors.newFixedThreadPool(64);
    private final Semaphore capacity = new Semaphore(32);
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();

    SocksProxy(InetAddress address, boolean udpEnabled) throws IOException {
        this.udpEnabled = udpEnabled;
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
                if (command != 1 && command != 3) { reply(output, 7); return; }
                String host = readHost(input);
                if (host == null) { reply(output, 8); return; }
                int port = input.readUnsignedShort();
                if (command == 3) {
                    if (!udpEnabled) reply(output, 7);
                    else relayUdp(client, port);
                    return;
                }
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

    private static String readHost(DataInputStream input) throws IOException {
        return switch (input.readUnsignedByte()) {
            case 1 -> InetAddress.getByAddress(read(input, 4)).getHostAddress();
            case 4 -> InetAddress.getByAddress(read(input, 16)).getHostAddress();
            case 3 -> new String(read(input, input.readUnsignedByte()), StandardCharsets.US_ASCII);
            default -> null;
        };
    }

    private void relayUdp(Socket control, int requestedPort) throws IOException {
        try (var relay = new DatagramSocket(new InetSocketAddress(control.getLocalAddress(), 0));
             var upstream = new DatagramSocket()) {
            var header = new DataOutputStream(control.getOutputStream());
            header.write(new byte[]{5, 0, 0});
            writeAddress(header, relay.getLocalAddress(), relay.getLocalPort());
            control.setSoTimeout(0);
            var peers = new ConcurrentHashMap<InetSocketAddress, Long>();
            var client = new java.util.concurrent.atomic.AtomicReference<InetSocketAddress>();
            var listener = new Thread(() -> {
                byte[] buffer = new byte[65535];
                var packet = new DatagramPacket(buffer, buffer.length);
                while (!relay.isClosed()) {
                    try {
                        packet.setLength(buffer.length);
                        relay.receive(packet);
                        if (!packet.getAddress().equals(control.getInetAddress())) continue;
                        if (requestedPort != 0 && packet.getPort() != requestedPort) continue;
                        var sender = (InetSocketAddress) packet.getSocketAddress();
                        var input = new DataInputStream(new ByteArrayInputStream(buffer, 0, packet.getLength()));
                        if (input.readUnsignedShort() != 0 || input.readUnsignedByte() != 0) continue;
                        String host = readHost(input);
                        int port = input.readUnsignedShort();
                        if (host == null || host.isBlank() || port == 0) continue;
                        client.compareAndSet(null, sender);
                        if (!sender.equals(client.get())) continue;
                        var destination = new InetSocketAddress(host, port);
                        if (destination.isUnresolved()) continue;
                        long now = System.nanoTime();
                        peers.entrySet().removeIf(entry -> now - entry.getValue() > TimeUnit.MINUTES.toNanos(2));
                        if (peers.size() >= 128 && !peers.containsKey(destination)) continue;
                        peers.put(destination, now);
                        byte[] payload = input.readAllBytes();
                        upstream.send(new DatagramPacket(payload, payload.length, destination));
                    } catch (IOException ignored) {}
                }
            }, "SOCKS UDP client");
            var receiver = new Thread(() -> {
                byte[] buffer = new byte[65535];
                var packet = new DatagramPacket(buffer, buffer.length);
                while (!upstream.isClosed()) {
                    try {
                        packet.setLength(buffer.length);
                        upstream.receive(packet);
                        Long sent = peers.get(packet.getSocketAddress());
                        var target = client.get();
                        if (target == null || sent == null ||
                            System.nanoTime() - sent > TimeUnit.MINUTES.toNanos(2)) continue;
                        var encoded = new ByteArrayOutputStream();
                        var output = new DataOutputStream(encoded);
                        output.write(new byte[]{0, 0, 0});
                        writeAddress(output, packet.getAddress(), packet.getPort());
                        output.write(buffer, 0, packet.getLength());
                        byte[] payload = encoded.toByteArray();
                        relay.send(new DatagramPacket(payload, payload.length, target));
                    } catch (IOException ignored) {}
                }
            }, "SOCKS UDP replies");
            listener.start();
            receiver.start();
            while (control.getInputStream().read() != -1) {
                // The association lives as long as its TCP control connection.
            }
        }
    }

    private static void writeAddress(DataOutputStream output, InetAddress address, int port) throws IOException {
        byte[] bytes = address.getAddress();
        output.writeByte(bytes.length == 4 ? 1 : 4);
        output.write(bytes);
        output.writeShort(port);
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
