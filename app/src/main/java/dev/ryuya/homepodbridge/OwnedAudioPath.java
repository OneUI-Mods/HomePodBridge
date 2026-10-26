package dev.ryuya.homepodbridge;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Per-connection lease; the backend is bound to one authenticated capture registration. */
final class OwnedAudioPath implements AutoCloseable {
  enum State {
    CHECKING,
    ACTIVE,
    STANDARD,
    RECOVERY_REQUIRED
  }

  record Update(State state, String report, int targetFrames) {
    Update(State state, String report) {
      this(state, report, 1856);
    }
  }

  interface Backend {
    String operate(int operation) throws Exception;
  }

  private final Backend backend;
  private final Executor executor;
  private final Consumer<Update> listener;
  private final int targetFrames;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicBoolean started = new AtomicBoolean();
  private boolean mutationPossible;
  private boolean restored;
  private boolean restorationAttempted;
  private volatile String restorationReport = "No restoration attempted";

  String restorationReport() {
    return restorationReport;
  }

  OwnedAudioPath(Backend backend, Executor executor, Consumer<Update> listener) {
    this(backend, executor, listener, 1856);
  }

  OwnedAudioPath(Backend backend, Executor executor, Consumer<Update> listener, int targetFrames) {
    if (targetFrames != 1856 && targetFrames != 928)
      throw new IllegalArgumentException("Unsupported playback reserve");
    this.backend = backend;
    this.executor = executor;
    this.listener = listener;
    this.targetFrames = targetFrames;
  }

  void start() {
    if (!started.compareAndSet(false, true)) return;
    executor.execute(
        () -> {
          if (closed.get()) return;
          emit(State.CHECKING, "Checking the owned device path");
          String report;
          try {
            report = backend.operate(targetFrames == 928 ? 4 : 2);
            mutationPossible =
                !report.contains("writeAttempts=0")
                    && (report.contains("Read-only owned route verified")
                        || report.contains("Owned reserve changed")
                        || report.contains("rollback failed"));
          } catch (Exception failure) {
            report = "Device path command failed: " + failure.getClass().getSimpleName();
            mutationPossible = true; // A lost reply must not be treated as proof of no mutation.
          }
          if (report.contains("Owned reserve changed: 2587 -> " + targetFrames)) {
            if (!closed.get()) emit(State.ACTIVE, report);
            // close() has already queued restoration if the client stopped during this command.
          } else {
            String restoration = restore();
            emit(
                restored || !mutationPossible ? State.STANDARD : State.RECOVERY_REQUIRED,
                report + "\n" + restoration);
          }
        });
  }

  private String restore() {
    if (!mutationPossible || restored) return "No restoration needed";
    if (restorationAttempted) return restorationReport;
    restorationAttempted = true;
    try {
      String report = backend.operate(targetFrames == 928 ? 5 : 3);
      restored =
          report.contains("Owned reserve changed: " + targetFrames + " -> 2587")
              || report.contains("Original reserve already present")
              || report.contains("No matching live owned route")
              || report.contains("owned registration absent");
      restorationReport = report;
      return report;
    } catch (Exception failure) {
      restorationReport = "Owned restoration unavailable: " + failure.getClass().getSimpleName();
      return restorationReport;
    }
  }

  private void emit(State state, String report) {
    if (!closed.get()) listener.accept(new Update(state, report, targetFrames));
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) executor.execute(this::restore);
  }
}
