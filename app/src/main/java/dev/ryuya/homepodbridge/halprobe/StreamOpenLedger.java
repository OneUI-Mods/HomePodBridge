package dev.ryuya.homepodbridge.halprobe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An undelivered open reply is potentially an allocation, never proof of no stream. */
final class StreamOpenLedger {
  enum Phase {
    OPENING,
    HANDLE_RECEIVED,
    CLOSED
  }

  record Entry(int configId, boolean input, Phase phase) {}

  interface Journal {
    void persist(List<Entry> entries) throws Exception;
  }

  private final Map<Integer, Entry> entries = new LinkedHashMap<>();
  private final Journal journal;

  StreamOpenLedger(Journal journal, List<Entry> recovered) {
    this.journal = journal;
    for (Entry entry : recovered) {
      if (entry.configId() <= 0 || entries.putIfAbsent(entry.configId(), entry) != null)
        throw new IllegalArgumentException("Invalid stream journal");
    }
  }

  synchronized void begin(int config, boolean input) throws Exception {
    if (config <= 0 || entries.containsKey(config))
      throw new IllegalStateException("Open already attempted");
    entries.put(config, new Entry(config, input, Phase.OPENING));
    // Persist before Binder dispatch. Failure prevents dispatch and still fails closed.
    journal.persist(snapshot());
  }

  synchronized void received(int config) throws Exception {
    transition(config, Phase.OPENING, Phase.HANDLE_RECEIVED);
  }

  synchronized void closed(int config) throws Exception {
    transition(config, Phase.HANDLE_RECEIVED, Phase.CLOSED);
  }

  private void transition(int config, Phase expected, Phase next) throws Exception {
    Entry previous = entries.get(config);
    if (previous == null || previous.phase() != expected)
      throw new IllegalStateException("Invalid stream transition");
    // Journal first: a failed save must never report successful cleanup.
    var proposed = new LinkedHashMap<>(entries);
    proposed.put(config, new Entry(config, previous.input(), next));
    journal.persist(List.copyOf(proposed.values()));
    entries.clear();
    entries.putAll(proposed);
  }

  synchronized List<Entry> snapshot() {
    return List.copyOf(entries.values());
  }

  synchronized boolean allClosed() {
    return entries.values().stream().allMatch(e -> e.phase() == Phase.CLOSED);
  }

  synchronized boolean attempted() {
    return !entries.isEmpty();
  }

  synchronized boolean replyUncertain() {
    return entries.values().stream().anyMatch(e -> e.phase() == Phase.OPENING);
  }
}
