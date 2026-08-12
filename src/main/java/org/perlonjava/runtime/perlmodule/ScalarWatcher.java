package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.*;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicLong;

/** Java implementation of Scalar::Watcher's small XS magic interface. */
public final class ScalarWatcher extends PerlModuleBase {
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final String CANCELLER = "Scalar::Watcher::Canceller";
    private static final String STATE = "_perlonjava_state";

    private record Cancellation(WeakReference<RuntimeScalar> target, long id) {}

    public ScalarWatcher() {
        super("Scalar::Watcher", false);
    }

    public static void initialize() {
        ScalarWatcher module = new ScalarWatcher();
        try {
            module.registerMethod("when_modified", "when_modified", "$&");
            module.registerMethod("when_destroyed", "when_destroyed", "$&");
            module.registerMethodInPackage(CANCELLER, "DESTROY", "cancel");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Missing Scalar::Watcher method", e);
        }
    }

    public static RuntimeList when_modified(RuntimeArray args, int ctx) {
        return install(args, ctx, false);
    }

    public static RuntimeList when_destroyed(RuntimeArray args, int ctx) {
        return install(args, ctx, true);
    }

    private static RuntimeList install(RuntimeArray args, int ctx, boolean destroyed) {
        if (args.size() < 2 || args.get(1).type != RuntimeScalarType.CODE) {
            throw new IllegalArgumentException("expected a CODE reference for watcher handler");
        }
        RuntimeScalar target = args.get(0);
        RuntimeScalar callback = args.get(1);
        long id = NEXT_ID.incrementAndGet();
        if (destroyed) target.addDestroyedWatcher(id, callback);
        else target.addModifiedWatcher(id, callback);

        if (ctx == RuntimeContextType.VOID) return new RuntimeList();

        RuntimeHash state = new RuntimeHash();
        state.put(STATE, new RuntimeScalar(new Cancellation(new WeakReference<>(target), id)));
        RuntimeScalar canceller = ReferenceOperators.bless(
                state.createReferenceWithTrackedElements(), new RuntimeScalar(CANCELLER));
        return canceller.getList();
    }

    public static RuntimeList cancel(RuntimeArray args, int ctx) {
        if (!args.isEmpty()) {
            RuntimeHash state = args.get(0).hashDeref();
            RuntimeScalar holder = state.get(STATE);
            if (holder != null && holder.value instanceof Cancellation cancellation) {
                RuntimeScalar target = cancellation.target().get();
                if (target != null) target.removeWatcher(cancellation.id());
            }
        }
        return new RuntimeList();
    }
}
