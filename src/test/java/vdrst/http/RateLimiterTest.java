package vdrst.http;

import vdrst.harness.Assert;
import vdrst.harness.Test;

/**
 * The limiter's clock is injectable, so these tests walk through minutes of traffic
 * without sleeping through any of it.
 */
public final class RateLimiterTest {

    private static final long SECOND = 1_000_000_000L;

    @Test("a client gets its burst immediately and is then refused")
    public void burstThenRefusal() {
        RateLimiter limiter = new RateLimiter(30, 5);
        long now = 0;
        for (int i = 0; i < 5; i++) {
            Assert.isTrue(limiter.tryAcquire("a", now), "request " + i + " of the burst was refused");
        }
        Assert.isTrue(!limiter.tryAcquire("a", now), "the request after the burst was allowed");
    }

    @Test("tokens refill at the configured rate")
    public void refillsOverTime() {
        RateLimiter limiter = new RateLimiter(60, 5);          // one token per second
        long now = 0;
        for (int i = 0; i < 5; i++) limiter.tryAcquire("a", now);
        Assert.isTrue(!limiter.tryAcquire("a", now), "bucket should be empty");

        Assert.isTrue(limiter.tryAcquire("a", now + SECOND), "one second should buy one token");
        Assert.isTrue(!limiter.tryAcquire("a", now + SECOND), "and only one");

        // A long quiet period refills to the burst ceiling, not beyond it.
        long later = now + 3_600 * SECOND;
        for (int i = 0; i < 5; i++) {
            Assert.isTrue(limiter.tryAcquire("a", later), "refill after idle was short");
        }
        Assert.isTrue(!limiter.tryAcquire("a", later), "refill exceeded the burst ceiling");
    }

    @Test("clients do not share buckets")
    public void clientsAreIndependent() {
        RateLimiter limiter = new RateLimiter(30, 3);
        long now = 0;
        for (int i = 0; i < 3; i++) limiter.tryAcquire("a", now);
        Assert.isTrue(!limiter.tryAcquire("a", now), "a should be exhausted");
        Assert.isTrue(limiter.tryAcquire("b", now), "b was throttled by a's traffic");
    }
}
