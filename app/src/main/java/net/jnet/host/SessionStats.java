package net.jnet.host;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class SessionStats {
    final AtomicInteger active = new AtomicInteger();
    final AtomicLong accepted = new AtomicLong();
    final AtomicLong sent = new AtomicLong();
    final AtomicLong received = new AtomicLong();
    final AtomicLong lastUdp = new AtomicLong();
    private final HashSet<String> peers = new HashSet<>();
    private final ArrayDeque<String> events = new ArrayDeque<>();

    synchronized void accept(String peer) {
        accepted.incrementAndGet();
        if (peers.size() < 256) peers.add(peer);
    }

    synchronized String devices() {
        return peers.size() == 256 ? "256+" : Integer.toString(peers.size());
    }

    synchronized void note(String message) {
        if (events.size() == 40) events.removeFirst();
        events.addLast(Instant.now() + "  " + message);
    }

    synchronized String log() {
        return events.isEmpty() ? "No events recorded." : String.join("\n", events);
    }
}
