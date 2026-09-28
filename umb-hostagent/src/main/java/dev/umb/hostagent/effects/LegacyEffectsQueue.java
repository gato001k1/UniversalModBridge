package dev.umb.hostagent.effects;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe bounded-by-drain queue. Producers may be legacy packet handlers; replay is client tick. */
public final class LegacyEffectsQueue {
    private final ConcurrentLinkedQueue<LegacyEffect> queue = new ConcurrentLinkedQueue<>();
    private final AtomicLong unknown = new AtomicLong();
    public void offer(LegacyEffect effect) { if (effect instanceof LegacyEffect.Unknown) unknown.incrementAndGet(); queue.add(effect); }
    public int drainTo(java.util.function.Consumer<LegacyEffect> consumer, int max) {
        int n = 0; LegacyEffect e;
        while (n < max && (e = queue.poll()) != null) { consumer.accept(e); n++; }
        return n;
    }
    public long unknownCount() { return unknown.get(); }
    public int size() { return queue.size(); }
}
