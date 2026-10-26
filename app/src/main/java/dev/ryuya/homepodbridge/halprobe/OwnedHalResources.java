package dev.ryuya.homepodbridge.halprobe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Prototype cleanup policy. A backend is permanently bound to one module Binder instance. */
final class OwnedHalResources {
  record Port(int id, int type, String address) {}

  record Config(int id, int portId, int handle) {}

  record Patch(int id, List<Integer> sources, List<Integer> sinks) {}

  record Snapshot(String epoch, List<Port> ports, List<Config> configs, List<Patch> patches) {}

  record Tag(String address, int outputMix, int inputMix, int outputHandle, int inputHandle) {}

  record Result(boolean complete, boolean oldInstanceGone, List<String> errors) {}

  interface Backend {
    Snapshot snapshot() throws Exception;

    boolean streamsClosed();

    void removePatch(int id) throws Exception;

    void removeConfig(int id) throws Exception;

    void disconnectPort(int id) throws Exception;
  }

  private final Backend backend;
  private final Snapshot baseline;
  private final Tag tag;
  private final Set<Integer> originalPorts, originalConfigs, originalPatches;

  OwnedHalResources(Backend backend, Snapshot baseline, Tag tag) {
    if (tag.address() == null
        || !tag.address().startsWith("HomePodBridgeDirect:")
        || tag.address().length() < 40
        || tag.outputHandle() == tag.inputHandle()
        || tag.outputHandle() == 0
        || tag.inputHandle() == 0) throw new IllegalArgumentException();
    this.backend = backend;
    this.baseline = baseline;
    this.tag = tag;
    originalPorts = ids(baseline.ports(), Port::id);
    originalConfigs = ids(baseline.configs(), Config::id);
    originalPatches = ids(baseline.patches(), Patch::id);
    if (baseline.ports().stream().anyMatch(p -> tag.address().equals(p.address()))
        || baseline.configs().stream()
            .anyMatch(c -> c.handle() == tag.inputHandle() || c.handle() == tag.outputHandle()))
      throw new IllegalArgumentException("Marker already exists");
  }

  interface Id<T> {
    int get(T value);
  }

  private static <T> Set<Integer> ids(List<T> values, Id<T> id) {
    Set<Integer> result = new HashSet<>();
    for (T value : values)
      if (id.get(value) <= 0 || !result.add(id.get(value)))
        throw new IllegalArgumentException("Invalid resource IDs");
    return result;
  }

  private Set<Integer> ports(Snapshot state) {
    ids(state.ports(), Port::id);
    Set<Integer> own = new HashSet<>();
    for (Port p : state.ports())
      if (!originalPorts.contains(p.id())
          && tag.address().equals(p.address())
          && (p.type() == 11 || p.type() == 143)) own.add(p.id());
    return own;
  }

  private Set<Integer> configs(Snapshot state) {
    ids(state.configs(), Config::id);
    Set<Integer> ownPorts = ports(state), own = new HashSet<>();
    Map<Integer, Integer> matches = new HashMap<>();
    for (Config c : state.configs())
      if (!originalConfigs.contains(c.id()) && mix(c)) matches.merge(c.handle(), 1, Integer::sum);
    for (Config c : state.configs())
      if (!originalConfigs.contains(c.id())
          && (ownPorts.contains(c.portId()) || (mix(c) && matches.get(c.handle()) == 1)))
        own.add(c.id());
    return own;
  }

  private boolean mix(Config c) {
    return (c.portId() == tag.outputMix() && c.handle() == tag.outputHandle())
        || (c.portId() == tag.inputMix() && c.handle() == tag.inputHandle());
  }

  private boolean patch(Patch p, Set<Integer> ownConfigs) {
    return !originalPatches.contains(p.id())
        && !p.sources().isEmpty()
        && !p.sinks().isEmpty()
        && ownConfigs.containsAll(p.sources())
        && ownConfigs.containsAll(p.sinks());
  }

  private boolean gone(Snapshot state) {
    return !baseline.epoch().equals(state.epoch());
  }

  private static boolean references(Patch p, int config) {
    return p.sources().contains(config) || p.sinks().contains(config);
  }

  synchronized Result cleanup() {
    List<String> errors = new ArrayList<>();
    try {
      Snapshot state = backend.snapshot();
      if (gone(state)) return new Result(true, true, errors);
      if (!backend.streamsClosed())
        return new Result(false, false, List.of("Owned streams must close first"));
      ids(state.patches(), Patch::id);
      Set<Integer> own = configs(state);
      for (Patch candidate : state.patches())
        if (patch(candidate, own)) {
          Snapshot current = backend.snapshot();
          if (gone(current)) return new Result(true, true, errors);
          Set<Integer> currentConfigs = configs(current);
          if (current.patches().stream()
              .anyMatch(p -> p.equals(candidate) && patch(p, currentConfigs)))
            try {
              backend.removePatch(candidate.id());
            } catch (Exception failure) {
              errors.add("Patch reply unavailable");
            }
        }
      state = backend.snapshot();
      if (gone(state)) return new Result(true, true, errors);
      for (Config candidate : state.configs())
        if (configs(state).contains(candidate.id())) {
          Snapshot current = backend.snapshot();
          if (gone(current)) return new Result(true, true, errors);
          if (configs(current).contains(candidate.id())
              && current.configs().contains(candidate)
              && current.patches().stream().noneMatch(p -> references(p, candidate.id())))
            try {
              backend.removeConfig(candidate.id());
            } catch (Exception failure) {
              errors.add("Config reply unavailable");
            }
        }
      state = backend.snapshot();
      if (gone(state)) return new Result(true, true, errors);
      for (Port candidate : state.ports())
        if (ports(state).contains(candidate.id())) {
          Snapshot current = backend.snapshot();
          if (gone(current)) return new Result(true, true, errors);
          if (ports(current).contains(candidate.id())
              && current.ports().contains(candidate)
              && current.configs().stream().noneMatch(c -> c.portId() == candidate.id()))
            try {
              backend.disconnectPort(candidate.id());
            } catch (Exception failure) {
              errors.add("Port reply unavailable");
            }
        }
      state = backend.snapshot();
      if (gone(state)) return new Result(true, true, errors);
      boolean ambiguous =
          state.configs().stream().anyMatch(c -> !originalConfigs.contains(c.id()) && mix(c));
      return new Result(
          ports(state).isEmpty() && configs(state).isEmpty() && !ambiguous, false, errors);
    } catch (Exception failure) {
      errors.add("Cleanup state unavailable: " + failure.getClass().getSimpleName());
      return new Result(false, false, errors);
    }
  }
}
