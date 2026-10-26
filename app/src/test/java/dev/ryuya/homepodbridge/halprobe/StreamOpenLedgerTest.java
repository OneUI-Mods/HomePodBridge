package dev.ryuya.homepodbridge.halprobe;

import java.util.List;

public final class StreamOpenLedgerTest {
  static void check(boolean value) {
    if (!value) throw new AssertionError();
  }

  static final class Disk implements StreamOpenLedger.Journal {
    List<StreamOpenLedger.Entry> saved = List.of();
    boolean fail;

    public void persist(List<StreamOpenLedger.Entry> entries) throws Exception {
      if (fail) throw new java.io.IOException("journal unavailable");
      saved = List.copyOf(entries);
    }
  }

  @org.junit.Test
  public void lifecycleFaultCases() throws Exception {
    main(new String[0]);
  }

  public static void main(String[] args) throws Exception {
    Disk disk = new Disk();
    var lease = new StreamOpenLedger(disk, List.of());
    check(lease.allClosed() && !lease.attempted());
    lease.begin(201, false);
    check(lease.attempted() && lease.replyUncertain() && !lease.allClosed());
    // HAL allocated, reply lost, caller died: recovery must not reset any resources.
    var recovered = new StreamOpenLedger(disk, disk.saved);
    check(recovered.replyUncertain() && !recovered.allClosed());
    var fake = new OwnedHalResourcesTest.Fake();
    fake.createThrough(8);
    fake.closed = recovered.allClosed();
    check(
        !new OwnedHalResources(fake, OwnedHalResourcesTest.BASE, OwnedHalResourcesTest.TAG)
            .cleanup()
            .complete());
    check(fake.removed.isEmpty());
    try {
      recovered.begin(201, false);
      throw new AssertionError();
    } catch (IllegalStateException expected) {
    }
    try {
      recovered.closed(201);
      throw new AssertionError();
    } catch (IllegalStateException expected) {
    }
    lease.received(201);
    check(!lease.replyUncertain() && !lease.allClosed());
    disk.fail = true;
    try {
      lease.closed(201);
      throw new AssertionError();
    } catch (java.io.IOException expected) {
    }
    check(!lease.allClosed());
    disk.fail = false;
    lease.closed(201);
    check(lease.allClosed());
    check(new StreamOpenLedger(disk, disk.saved).allClosed());
    Disk broken = new Disk();
    broken.fail = true;
    var noDispatch = new StreamOpenLedger(broken, List.of());
    try {
      noDispatch.begin(202, true);
      throw new AssertionError();
    } catch (java.io.IOException expected) {
    }
    check(!noDispatch.allClosed());
    System.out.println(
        "{\"passed\":true,\"guards\":[\"lost open reply\",\"recovery gate\",\"no duplicate"
            + " open\",\"journal before dispatch\",\"close journal failure\"]}");
  }
}
