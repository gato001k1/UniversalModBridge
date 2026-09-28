package dev.umb.hostagent.input;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Server seam for the parallel legacy input consumer. Latest frame wins per player. */
public final class LegacyInputDispatcher {
    public interface Sink { void accept(LegacyInputFrame frame); }
    private final Sink sink;
    private final Map<String, LegacyInputFrame> latest = new ConcurrentHashMap<>();
    public LegacyInputDispatcher(Sink sink) { this.sink = sink; }
    public void receive(LegacyInputPayload payload) { latest.put(payload.frame().playerId(), payload.frame()); }
    public void dispatchTick() { latest.values().stream().sorted(java.util.Comparator.comparing(LegacyInputFrame::playerId))
            .forEach(sink::accept); }
    public LegacyInputFrame latest(String playerId) { return latest.get(playerId); }
}
