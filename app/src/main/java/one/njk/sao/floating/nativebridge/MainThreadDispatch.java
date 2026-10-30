package one.njk.sao.floating.nativebridge;

import android.os.Handler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** A queued timeout cancels dispatch; a running timeout cannot prove rejection. */
final class MainThreadDispatch {
    private enum Phase { QUEUED, RUNNING, CANCELLED, ACCEPTED, REJECTED, UNKNOWN }
    static NativeInsertResult run(Handler main, BooleanSupplier operation, long timeoutMillis) {
        return run(main, operation, timeoutMillis, () -> { });
    }
    static NativeInsertResult run(Handler main, BooleanSupplier operation, long timeoutMillis, Runnable cleanup) {
        AtomicReference<Phase> phase = new AtomicReference<>(Phase.QUEUED);
        AtomicBoolean disposed = new AtomicBoolean();
        Runnable dispose = () -> { if (disposed.compareAndSet(false, true)) cleanup.run(); };
        CountDownLatch completed = new CountDownLatch(1);
        if (!main.post(() -> {
            try {
                if (!phase.compareAndSet(Phase.QUEUED, Phase.RUNNING)) return;
                try { phase.set(operation.getAsBoolean() ? Phase.ACCEPTED : Phase.REJECTED); }
                catch (RuntimeException | LinkageError error) { phase.set(Phase.UNKNOWN); }
            } finally { dispose.run(); completed.countDown(); }
        })) { dispose.run(); return NativeInsertResult.REJECTED; }
        try { completed.await(timeoutMillis, TimeUnit.MILLISECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        if (phase.compareAndSet(Phase.QUEUED, Phase.CANCELLED)) { dispose.run(); return NativeInsertResult.REJECTED; }
        return switch (phase.get()) {
            case ACCEPTED -> NativeInsertResult.ACCEPTED;
            case REJECTED, CANCELLED -> NativeInsertResult.REJECTED;
            default -> NativeInsertResult.UNKNOWN;
        };
    }
    private MainThreadDispatch() { }
}
