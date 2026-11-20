package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.AutoboxRuntime;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

/** Java implementation of autobox::universal's scalar type inspection. */
public final class autoboxuniversal extends PerlModuleBase {
    public autoboxuniversal() {
        super("autobox::universal");
    }

    public static void initialize() {
        autoboxuniversal module = new autoboxuniversal();
        module.initializeExporter();
        GlobalVariable.getGlobalVariable("autobox::universal::VERSION").set("3.0.2");
        module.defineExport("EXPORT_OK", "type");
        try {
            module.registerMethod("type", "$");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Missing autobox::universal::type implementation", e);
        }
    }

    public static RuntimeList type(RuntimeArray args, int ctx) {
        RuntimeScalar value = args.isEmpty() ? null : args.getFirst();
        return new RuntimeScalar(AutoboxRuntime.autoboxType(value)).getList();
    }
}
