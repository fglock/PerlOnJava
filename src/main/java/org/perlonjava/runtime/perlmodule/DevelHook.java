package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

/** Java implementation of the special-block queue accessors used by Devel::Hook. */
public class DevelHook extends PerlModuleBase {
    public static final String XS_VERSION = "0.011";

    public DevelHook() {
        super("Devel::Hook", false);
    }

    public static void initialize() {
        DevelHook module = new DevelHook();
        try {
            module.registerMethod("_get_begin_array", null);
            module.registerMethod("_get_unitcheck_array", null);
            module.registerMethod("_get_check_array", null);
            module.registerMethod("_get_init_array", null);
            module.registerMethod("_get_end_array", null);
            module.registerMethod("_get_supported_types", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RuntimeList arrayRef(RuntimeArray array) {
        return array.createReference().getList();
    }

    public static RuntimeList _get_begin_array(RuntimeArray args, int ctx) {
        // BEGIN blocks execute immediately and therefore have no pending queue.
        return arrayRef(new RuntimeArray());
    }

    public static RuntimeList _get_unitcheck_array(RuntimeArray args, int ctx) {
        var queues = PerlRuntime.current().compilationState.unitcheckQueueStack.get();
        if (queues.isEmpty()) {
            throw new IllegalStateException("UNITCHECK queue is unavailable outside compilation");
        }
        return arrayRef(queues.peek());
    }

    public static RuntimeList _get_check_array(RuntimeArray args, int ctx) {
        return arrayRef(SpecialBlock.getCheckBlocks());
    }

    public static RuntimeList _get_init_array(RuntimeArray args, int ctx) {
        return arrayRef(SpecialBlock.getInitBlocks());
    }

    public static RuntimeList _get_end_array(RuntimeArray args, int ctx) {
        return arrayRef(SpecialBlock.getEndBlocks());
    }

    public static RuntimeList _get_supported_types(RuntimeArray args, int ctx) {
        RuntimeHash supported = new RuntimeHash();
        for (String phase : new String[] {"BEGIN", "UNITCHECK", "CHECK", "INIT", "END"}) {
            supported.put(phase, RuntimeScalarCache.scalarTrue);
        }
        return supported.createReference().getList();
    }
}
