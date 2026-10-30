package org.perlonjava.runtime.perlmodule;

import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.info.GraphLayout;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

/** JVM object footprint estimates exposed by Devel::Size. */
public class DevelSize extends PerlModuleBase {

    static {
        // The launcher may not permit dynamic agent attachment. JOL's Unsafe
        // layout strategy still works without it; avoid warning on every call.
        System.setProperty("jol.skipInstallAttach", "true");
        System.setProperty("jol.skipDynamicAttach", "true");
    }

    public DevelSize() {
        super("Devel::Size", false);
    }

    public static void initialize() {
        DevelSize module = new DevelSize();
        try {
            module.registerMethod("size", null);
            module.registerMethod("total_size", null);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
        module.defineExport("EXPORT_OK", "size", "total_size");
        module.defineDefaultAndAllTags();
    }

    /** Return the shallow JVM size of the Perl scalar and its inline payload. */
    public static RuntimeList size(RuntimeArray args, int ctx) {
        if (args == null || args.isEmpty() || args.get(0) == null) {
            return new RuntimeScalar(0L).getList();
        }
        RuntimeScalar scalar = args.get(0);
        long bytes = ClassLayout.parseInstance(scalar).instanceSize();
        if (!org.perlonjava.runtime.runtimetypes.RuntimeScalarType.isReference(scalar)
                && scalar.value != null) {
            bytes += GraphLayout.parseInstance(scalar.value).totalSize();
        }
        return new RuntimeScalar(bytes).getList();
    }

    /** Return the JVM footprint of all objects reachable from the scalar. */
    public static RuntimeList total_size(RuntimeArray args, int ctx) {
        if (args == null || args.isEmpty() || args.get(0) == null) {
            return new RuntimeScalar(0L).getList();
        }
        long bytes = GraphLayout.parseInstance(args.get(0)).totalSize();
        return new RuntimeScalar(bytes).getList();
    }
}
