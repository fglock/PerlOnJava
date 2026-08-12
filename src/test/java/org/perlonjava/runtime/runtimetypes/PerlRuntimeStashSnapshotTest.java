package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class PerlRuntimeStashSnapshotTest {

    @Test
    void stashCloneDoesNotReplayTypeglobWritesOverClonedArrays() throws Exception {
        for (boolean interpreter : new boolean[]{false, true}) {
            PerlRuntime parent = new PerlRuntime();
            try {
                run(parent, interpreter, ""
                        + "our @ordinary = ('ordinary'); "
                        + "package SnapshotParent; sub inherited { 42 } "
                        + "package SnapshotChild; our @ISA = ('SnapshotParent'); "
                        + "package main; 1");

                PerlRuntime child = parent.snapshotClone();
                try {
                    assertEquals("1:ordinary:1:SnapshotParent:42",
                            run(child, interpreter, "join q(:), "
                                    + "scalar(@ordinary), @ordinary, "
                                    + "scalar(@SnapshotChild::ISA), @SnapshotChild::ISA, "
                                    + "SnapshotChild->inherited"));
                    assertEquals("1:ordinary:1:SnapshotParent:42",
                            run(parent, interpreter, "join q(:), "
                                    + "scalar(@ordinary), @ordinary, "
                                    + "scalar(@SnapshotChild::ISA), @SnapshotChild::ISA, "
                                    + "SnapshotChild->inherited"));
                } finally {
                    child.close();
                }
            } finally {
                parent.close();
            }
        }
    }

    private static String run(PerlRuntime runtime, boolean interpreter, String source)
            throws Exception {
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            CompilerOptions options = new CompilerOptions();
            options.fileName = "<runtime-stash-snapshot>";
            options.useInterpreter = interpreter;
            options.code = source;
            return PerlLanguageProvider.executePerlCode(options, false).scalar().toString();
        }
    }
}
