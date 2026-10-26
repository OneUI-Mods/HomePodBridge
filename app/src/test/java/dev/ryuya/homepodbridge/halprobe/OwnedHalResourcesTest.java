package dev.ryuya.homepodbridge.halprobe;

import java.util.ArrayList;
import java.util.List;

public final class OwnedHalResourcesTest {
  static final String ADDRESS = "HomePodBridgeDirect:12345678-1234-5678-1234-567812345678";
  static final OwnedHalResources.Tag TAG = new OwnedHalResources.Tag(ADDRESS, 3, 4, 90001, 90002);
  static final OwnedHalResources.Snapshot BASE =
      new OwnedHalResources.Snapshot(
          "old",
          List.of(
              new OwnedHalResources.Port(1, 143, ""),
              new OwnedHalResources.Port(2, 11, ""),
              new OwnedHalResources.Port(3, 0, ""),
              new OwnedHalResources.Port(4, 0, ""),
              new OwnedHalResources.Port(5, 11, "0")),
          List.of(
              new OwnedHalResources.Config(974, 5, 0), new OwnedHalResources.Config(975, 4, 2190)),
          List.of(new OwnedHalResources.Patch(7, List.of(974), List.of(975))));

  static void check(boolean condition) {
    if (!condition) throw new AssertionError();
  }

  static final class Fake implements OwnedHalResources.Backend {
    String epoch = "old";
    boolean closed = true, lostReply = false;
    List<OwnedHalResources.Port> ports = new ArrayList<>(BASE.ports());
    List<OwnedHalResources.Config> configs = new ArrayList<>(BASE.configs());
    List<OwnedHalResources.Patch> patches = new ArrayList<>(BASE.patches());
    List<Integer> removed = new ArrayList<>();

    public OwnedHalResources.Snapshot snapshot() {
      return new OwnedHalResources.Snapshot(
          epoch, List.copyOf(ports), List.copyOf(configs), List.copyOf(patches));
    }

    public boolean streamsClosed() {
      return closed;
    }

    void reply() throws Exception {
      if (lostReply) throw new java.io.IOException("Lost reply after mutation");
    }

    public void removePatch(int id) throws Exception {
      check(id == 301 || id == 302);
      removed.add(id);
      patches.removeIf(p -> p.id() == id);
      reply();
    }

    public void removeConfig(int id) throws Exception {
      check(id >= 201 && id <= 204);
      removed.add(id);
      configs.removeIf(c -> c.id() == id);
      reply();
    }

    public void disconnectPort(int id) throws Exception {
      check(id == 101 || id == 102);
      removed.add(id);
      ports.removeIf(p -> p.id() == id);
      reply();
    }

    void createThrough(int count) {
      if (count >= 1) ports.add(new OwnedHalResources.Port(101, 143, ADDRESS));
      if (count >= 2) ports.add(new OwnedHalResources.Port(102, 11, ADDRESS));
      if (count >= 3) configs.add(new OwnedHalResources.Config(201, 3, 90001));
      if (count >= 4) configs.add(new OwnedHalResources.Config(202, 4, 90002));
      if (count >= 5) configs.add(new OwnedHalResources.Config(203, 101, 0));
      if (count >= 6) configs.add(new OwnedHalResources.Config(204, 102, 0));
      if (count >= 7) patches.add(new OwnedHalResources.Patch(301, List.of(201), List.of(203)));
      if (count >= 8) patches.add(new OwnedHalResources.Patch(302, List.of(204), List.of(202)));
    }
  }

  @org.junit.Test
  public void lifecycleFaultCases() throws Exception {
    main(new String[0]);
  }

  public static void main(String[] args) {
    for (int stage = 0; stage <= 8; stage++)
      for (boolean lose : new boolean[] {false, true}) {
        Fake fake = new Fake();
        fake.createThrough(stage);
        fake.lostReply = lose;
        var lease = new OwnedHalResources(fake, BASE, TAG);
        check(lease.cleanup().complete());
        check(fake.snapshot().equals(BASE));
        int calls = fake.removed.size();
        check(lease.cleanup().complete());
        check(fake.removed.size() == calls);
      }
    Fake live = new Fake();
    live.createThrough(8);
    live.closed = false;
    var lease = new OwnedHalResources(live, BASE, TAG);
    check(!lease.cleanup().complete() && live.removed.isEmpty());
    live.closed = true;
    check(lease.cleanup().complete());
    Fake foreign = new Fake();
    foreign.createThrough(8);
    var adopted = new OwnedHalResources.Patch(399, List.of(201), List.of(975));
    foreign.patches.add(adopted);
    var guarded = new OwnedHalResources(foreign, BASE, TAG);
    check(!guarded.cleanup().complete());
    check(foreign.patches.contains(adopted));
    check(foreign.configs.contains(new OwnedHalResources.Config(201, 3, 90001)));
    check(
        foreign.patches.containsAll(BASE.patches()) && foreign.configs.containsAll(BASE.configs()));
    foreign.patches.remove(adopted);
    check(guarded.cleanup().complete());
    check(foreign.snapshot().equals(BASE));
    Fake restarted = new Fake();
    restarted.createThrough(8);
    restarted.epoch = "new";
    var replaced = new OwnedHalResources(restarted, BASE, TAG).cleanup();
    check(replaced.complete() && replaced.oldInstanceGone() && restarted.removed.isEmpty());
    Fake collision = new Fake();
    collision.createThrough(8);
    collision.configs.add(new OwnedHalResources.Config(9999, 3, 90001));
    check(!new OwnedHalResources(collision, BASE, TAG).cleanup().complete());
    check(
        !collision.removed.contains(201)
            && collision.configs.stream().anyMatch(c -> c.id() == 9999));
    System.out.println(
        "{\"passed\":true,\"partialAllocationAndLostReplyCases\":18,\"guards\":[\"live"
            + " streams\",\"foreign patch\",\"module restart\",\"ambiguous"
            + " marker\",\"idempotency\"]}");
  }
}
