package vdrst.http;

import java.util.concurrent.ConcurrentHashMap;

/**
 * A token bucket per client, for deployments that face the public internet.
 *
 * <p>The search itself is the only expensive thing this service does, and it is priced
 * in CPU: a single long query is milliseconds of a core, and nothing stops a loop from
 * submitting them back to back. On a private bench that is a benchmark; on a public URL
 * it is a denial of service. The limiter sits at the HTTP door, so the pipeline behind
 * it keeps the latency the benchmarks measure.
 *
 * <p>Buckets refill continuously: a client gets {@code burst} requests immediately and
 * {@code perMinute} sustained. State is two primitives per client, synchronised per
 * bucket, so contention is per-client rather than global. The map is bounded the crude
 * way — past {@code MAX_TRACKED} distinct clients it is cleared outright — because a
 * fairness-perfect LRU for an attack that large buys nothing: the attacker gets a fresh
 * burst either way, and everyone else gets one too.
 *
 * <p>Disabled entirely when {@code perMinute} is zero, which is the default — a service
 * on localhost has no door to guard.
 */
public final class RateLimiter {

    private static final int MAX_TRACKED = 65_536;

    private final double refillPerNano;
    private final double burst;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(int perMinute, int burst) {
        if (perMinute <= 0) throw new IllegalArgumentException("perMinute must be > 0");
        if (burst < 1) throw new IllegalArgumentException("burst must be >= 1");
        this.refillPerNano = perMinute / 60e9;
        this.burst = burst;
    }

    /** True when {@code client} may proceed now; false when it should be told to wait. */
    public boolean tryAcquire(String client) {
        return tryAcquire(client, System.nanoTime());
    }

    /** Clock-injected variant, so the tests need not sleep their way through a minute. */
    boolean tryAcquire(String client, long nowNanos) {
        if (buckets.size() > MAX_TRACKED) buckets.clear();

        Bucket bucket = buckets.computeIfAbsent(client, k -> new Bucket(nowNanos, burst));
        synchronized (bucket) {
            bucket.tokens = Math.min(burst, bucket.tokens + (nowNanos - bucket.last) * refillPerNano);
            bucket.last = nowNanos;
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    private static final class Bucket {
        long last;
        double tokens;

        Bucket(long now, double tokens) {
            this.last = now;
            this.tokens = tokens;
        }
    }
}
