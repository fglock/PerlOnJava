package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class GlobalDestructionPackageAggregateRootTest {
    @Test
    void dispatchesDestroyForBlessedGlobStorageRootsOnBothBackends() throws Exception {
        String source = """
                package Issue2043::GlobRoot;
                sub DESTROY { ++$main::issue2043_destroyed; }
                package main;
                my $scalar_cell;
                *scalar_root = bless \\$scalar_cell, 'Issue2043::GlobRoot';
                *array_root = bless [], 'Issue2043::GlobRoot';
                *hash_root = bless {}, 'Issue2043::GlobRoot';
                our @nested_array_root;
                @nested_array_root = ([\\@nested_array_root,
                    bless([], 'Issue2043::GlobRoot')]);
                our %nested_hash_root;
                %nested_hash_root = (root => { cycle => \\%nested_hash_root,
                    value => bless({}, 'Issue2043::GlobRoot') });
                """;

        for (boolean interpreter : new boolean[]{false, true}) {
            PerlRuntime runtime = new PerlRuntime().initialize();
            try (PerlRuntime.Binding ignored = runtime.bind()) {
                CompilerOptions options = new CompilerOptions();
                options.fileName = "<issue2043-glob-root-destroy>";
                options.code = source;
                options.useInterpreter = interpreter;
                PerlLanguageProvider.executePerlCode(options, false);

                GlobalDestruction.runGlobalDestruction();

                assertEquals(5,
                        GlobalVariable.getGlobalVariable("main::issue2043_destroyed").getInt(),
                        "direct and nested package aggregate targets receive one DESTROY call");
            }
        }
    }
}
