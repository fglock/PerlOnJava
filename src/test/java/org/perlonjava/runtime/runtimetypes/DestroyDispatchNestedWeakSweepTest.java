package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class DestroyDispatchNestedWeakSweepTest {

    @Test
    void weakChildIsRecheckedAfterBlessedContainerDestroyReturns() throws Exception {
        for (boolean interpreter : new boolean[]{false, true}) {
            PerlRuntime runtime = new PerlRuntime();
            try (PerlRuntime.Binding ignored = runtime.bind()) {
                execute(runtime, interpreter, ""
                        + "use Scalar::Util qw(weaken); "
                        + "package Issue1646::Storage; sub DESTROY { } "
                        + "package Issue1646::Schema; sub DESTROY { } "
                        + "package main; "
                        + "our $schema = bless { storage => bless({}, 'Issue1646::Storage') }, "
                        + "  'Issue1646::Schema'; "
                        + "our $weak_storage = $schema->{storage}; weaken($weak_storage); "
                        + "our $handle = { handler => sub { "
                        + "  $weak_storage ? 'DBI Exception' : 'unhandled by DBIC' "
                        + "} }; ");

                RuntimeScalar schemaSlot = GlobalVariable.getGlobalVariable("main::schema");
                RuntimeHash schema = (RuntimeHash) schemaSlot.value;
                RuntimeHash storage = (RuntimeHash) schema.elements.get("storage").value;
                // Model selectively counted owners that remain after a large
                // blessed owner graph is destroyed. The reachability walk must
                // still clear the weak callback once the schema has gone.
                storage.refCount = 132;
                schemaSlot.refCountOwned = false;
                schemaSlot.value = null;
                schemaSlot.type = RuntimeScalarType.UNDEF;
                schema.refCount = 0;
                DestroyDispatch.callDestroy(schema);

                assertEquals("unhandled by DBIC", execute(runtime, interpreter,
                        "$handle->{handler}->()"));
            } finally {
                runtime.close();
            }
        }
    }

    private static String execute(
            PerlRuntime runtime, boolean interpreter, String code) throws Exception {
        CompilerOptions options = new CompilerOptions();
        options.fileName = "<issue-1646-nested-weak-sweep>";
        options.useInterpreter = interpreter;
        options.code = code;
        return PerlLanguageProvider.executePerlCode(options, false).scalar().toString();
    }
}
