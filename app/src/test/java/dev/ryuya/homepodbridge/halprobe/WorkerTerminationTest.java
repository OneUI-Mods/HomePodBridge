package dev.ryuya.homepodbridge.halprobe;

import static org.junit.Assert.*;

import java.io.*;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class WorkerTerminationTest {
  static final class Pipe extends ByteArrayInputStream {
    boolean closed;

    Pipe(String data) {
      super(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    public synchronized int available() {
      if (closed) throw new IllegalStateException("Stream closed");
      return super.available();
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  static final class Child extends Process {
    final Pipe out = new Pipe("FAULT_CHECKPOINT=hang\n"), err = new Pipe("");
    boolean alive = true, neverStops;
    int code = 137;

    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    public InputStream getInputStream() {
      return out;
    }

    public InputStream getErrorStream() {
      return err;
    }

    public int waitFor() {
      return code;
    }

    public boolean waitFor(long timeout, TimeUnit unit) {
      return !alive;
    }

    public int exitValue() {
      if (alive) throw new IllegalThreadStateException();
      return code;
    }

    public void destroy() {
      destroyForcibly();
    }

    public Process destroyForcibly() {
      out.close();
      err.close();
      if (!neverStops) alive = false;
      return this;
    }

    public boolean isAlive() {
      return alive;
    }
  }

  @Test
  public void capturesCheckpointBeforeAndroidClosesPipesOnForcedExit() throws Exception {
    Child child = new Child();
    var outcome =
        WorkerTermination.collect(child, TimeUnit.SECONDS.toNanos(3), child::destroyForcibly);
    assertTrue(child.out.closed);
    assertTrue(outcome.forced());
    assertTrue(outcome.timedOut());
    assertEquals("FAULT_CHECKPOINT=hang\n", outcome.diagnostics());
    assertTrue(
        ProbeScenario.HANG.acceptsFailure(
            outcome.exitCode(), outcome.timedOut(), outcome.forced(), outcome.diagnostics()));
  }

  @Test
  public void earlyReadFailureCannotBeCalledTimeout() throws Exception {
    Child child = new Child();
    var outcome =
        WorkerTermination.collect(
            child, TimeUnit.MILLISECONDS.toNanos(200), child::destroyForcibly);
    assertFalse(outcome.timedOut());
    assertFalse(
        ProbeScenario.HANG.acceptsFailure(
            outcome.exitCode(), outcome.timedOut(), outcome.forced(), outcome.diagnostics()));
  }

  @Test
  public void naturalExitIsNeverClassifiedAsForcedHang() throws Exception {
    Child child = new Child();
    child.alive = false;
    child.code = 73;
    var outcome =
        WorkerTermination.collect(child, TimeUnit.SECONDS.toNanos(4), child::destroyForcibly);
    assertFalse(outcome.forced());
    assertFalse(outcome.timedOut());
    assertFalse(child.out.closed);
    assertFalse(
        ProbeScenario.HANG.acceptsFailure(
            outcome.exitCode(), outcome.timedOut(), outcome.forced(), outcome.diagnostics()));
  }

  @Test
  public void missingPipesStillRequireConfirmedTerminationAndCannotPassFault() throws Exception {
    Child child = new Child();
    child.out.close();
    var outcome =
        WorkerTermination.collect(child, TimeUnit.SECONDS.toNanos(3), child::destroyForcibly);
    assertFalse(child.alive);
    assertTrue(outcome.forced());
    assertEquals("WORKER_DIAGNOSTICS_UNAVAILABLE", outcome.diagnostics());
    assertFalse(
        ProbeScenario.HANG.acceptsFailure(
            outcome.exitCode(), outcome.timedOut(), outcome.forced(), outcome.diagnostics()));
  }

  @Test
  public void unconfirmedTerminationIsAnError() throws Exception {
    Child child = new Child();
    child.neverStops = true;
    try {
      WorkerTermination.collect(child, TimeUnit.SECONDS.toNanos(3), child::destroyForcibly);
      fail();
    } catch (IOException expected) {
      assertEquals("Worker termination unconfirmed", expected.getMessage());
    }
  }
}
