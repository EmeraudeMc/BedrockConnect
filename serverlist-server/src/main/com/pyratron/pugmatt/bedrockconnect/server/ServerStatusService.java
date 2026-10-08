package main.com.pyratron.pugmatt.bedrockconnect.server;

import main.com.pyratron.pugmatt.bedrockconnect.BedrockConnect;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Pings Bedrock servers (RakNet unconnected ping, the same request the game uses for its server list)
 * to show whether they are online and how many players they have.
 * Results are cached, and concurrent requests for the same server share a single ping.
 */
public final class ServerStatusService {

    public static final class Status {
        public static final Status OFFLINE = new Status(false, 0, 0);

        private final boolean online;
        private final int players;
        private final int maxPlayers;

        private Status(boolean online, int players, int maxPlayers) {
            this.online = online;
            this.players = players;
            this.maxPlayers = maxPlayers;
        }

        public boolean isOnline() { return online; }

        public int getPlayers() { return players; }

        public int getMaxPlayers() { return maxPlayers; }
    }

    /**
     * A server to check
     */
    public static final class Target {
        private final String host;
        private final int port;
        // Player-added servers may not point to private/local addresses, to avoid probing the host's network
        private final boolean allowPrivate;

        public Target(String host, int port, boolean allowPrivate) {
            this.host = host;
            this.port = port;
            this.allowPrivate = allowPrivate;
        }

        private String key() {
            return host.toLowerCase(Locale.ROOT) + ":" + port;
        }
    }

    private static final class Entry {
        private final CompletableFuture<Status> future;
        private final long createdAt = System.nanoTime();

        private Entry(CompletableFuture<Status> future) {
            this.future = future;
        }

        private boolean isFresh() {
            return !future.isDone() || System.nanoTime() - createdAt < CACHE_TTL_NANOS;
        }
    }

    private static final long CACHE_TTL_NANOS = TimeUnit.SECONDS.toNanos(30);
    // Entries not requested for this long are dropped, so the cache can't grow forever
    private static final long CACHE_EXPIRE_NANOS = TimeUnit.MINUTES.toNanos(10);
    private static final int[] ATTEMPT_TIMEOUTS_MS = {700, 500};
    // How long a form waits for statuses before being shown anyway (late results are used next time)
    private static final long FORM_WAIT_MS = 1200;

    private static final byte UNCONNECTED_PING = 0x01;
    private static final byte UNCONNECTED_PONG = 0x1c;
    private static final byte[] RAKNET_MAGIC = {
            0x00, (byte) 0xff, (byte) 0xff, 0x00, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe,
            (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, 0x12, 0x34, 0x56, 0x78
    };

    private static final ConcurrentHashMap<String, Entry> CACHE = new ConcurrentHashMap<>();

    private static final ExecutorService PING_EXECUTOR = Executors.newFixedThreadPool(16, r -> {
        Thread t = new Thread(r, "BedrockConnect-ServerStatus");
        t.setDaemon(true);
        return t;
    });

    private ServerStatusService() {}

    /**
     * Get the status of a server, pinging it if there is no recent result
     */
    public static CompletableFuture<Status> getStatus(Target target) {
        return CACHE.compute(target.key(), (key, entry) -> {
            if (entry != null && entry.isFresh())
                return entry;
            return new Entry(CompletableFuture.supplyAsync(() -> ping(target), PING_EXECUTOR));
        }).future;
    }

    /**
     * Last known status, without waiting. Null if unknown (not pinged yet, still pending, or skipped)
     */
    public static Status getCachedStatus(String host, int port) {
        Entry entry = CACHE.get(host.toLowerCase(Locale.ROOT) + ":" + port);
        return entry != null ? entry.future.getNow(null) : null;
    }

    /**
     * Refresh the given servers, completing once they're all done or after a short timeout
     */
    public static CompletableFuture<Void> refresh(Collection<Target> targets) {
        long now = System.nanoTime();
        CACHE.values().removeIf(e -> e.future.isDone() && now - e.createdAt > CACHE_EXPIRE_NANOS);

        if (targets.isEmpty())
            return CompletableFuture.completedFuture(null);

        CompletableFuture<?>[] futures = targets.stream()
                .map(ServerStatusService::getStatus)
                .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(futures)
                .completeOnTimeout(null, FORM_WAIT_MS, TimeUnit.MILLISECONDS)
                .exceptionally(e -> null);
    }

    private static Status ping(Target target) {
        try {
            InetAddress address = InetAddress.getByName(target.host);
            if (!target.allowPrivate && isPrivate(address))
                return null;

            try (DatagramSocket socket = new DatagramSocket()) {
                // Only accept replies from the pinged server
                socket.connect(address, target.port);

                byte[] request = createPing();
                byte[] buffer = new byte[2048];

                // UDP can drop packets: retry once before considering the server offline
                for (int timeout : ATTEMPT_TIMEOUTS_MS) {
                    socket.setSoTimeout(timeout);
                    socket.send(new DatagramPacket(request, request.length));
                    try {
                        DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                        socket.receive(response);
                        Status status = parsePong(buffer, response.getLength());
                        if (status != null)
                            return status;
                    } catch (SocketTimeoutException ignored) {
                        // Try again
                    }
                }
            }
        } catch (Exception e) {
            BedrockConnect.logger.debug("Status ping failed for " + target.host + ":" + target.port + " (" + e + ")");
        }
        return Status.OFFLINE;
    }

    private static byte[] createPing() {
        ByteBuffer buf = ByteBuffer.allocate(1 + 8 + RAKNET_MAGIC.length + 8);
        buf.put(UNCONNECTED_PING);
        buf.putLong(System.currentTimeMillis());
        buf.put(RAKNET_MAGIC);
        buf.putLong(ThreadLocalRandom.current().nextLong());
        return buf.array();
    }

    /**
     * Pong data: "MCPE;motd;protocol;version;players;maxPlayers;..."
     */
    private static Status parsePong(byte[] data, int length) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
            if (buf.get() != UNCONNECTED_PONG)
                return null;
            buf.getLong(); // Ping time
            buf.getLong(); // Server GUID
            buf.position(buf.position() + RAKNET_MAGIC.length);
            int strLength = buf.getShort() & 0xffff;
            String info = new String(data, buf.position(), Math.min(strLength, buf.remaining()), StandardCharsets.UTF_8);

            String[] parts = info.split(";");
            if (parts.length < 6)
                return new Status(true, 0, 0);
            return new Status(true, parseInt(parts[4]), parseInt(parts[5]));
        } catch (BufferUnderflowException | IllegalArgumentException e) {
            return null;
        }
    }

    private static int parseInt(String value) {
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean isPrivate(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress())
            return true;

        byte[] b = address.getAddress();
        if (b.length == 4) {
            // Carrier-grade NAT 100.64.0.0/10
            return (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;
        }
        // IPv6 unique local fc00::/7
        return (b[0] & 0xfe) == 0xfc;
    }
}
