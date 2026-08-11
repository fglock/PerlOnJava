package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class GlobalCodeRuntimeIsolationTest {

    @Test
    void codeSlotsPinsCompiledIdsAndImportedSubFlagsBelongToTheBoundRuntime() {
        PerlRuntime first = new PerlRuntime();
        PerlRuntime second = new PerlRuntime();
        String name = "Phase8bLowLevel::same_name";
        RuntimeScalar firstCode = codeScalar("first", "$", name);
        RuntimeScalar secondCode = codeScalar("second", "$$", name);
        RuntimeScalar firstPinned;
        int firstCompiledId;

        try (PerlRuntime.Binding ignored = first.bind()) {
            firstPinned = GlobalVariable.getGlobalCodeRef(name);
            firstPinned.set(firstCode);
            GlobalVariable.isSubs.put(name, true);
            GlobalVariable.globalGlobs.put(name, true);
            firstCompiledId = GlobalVariable.registerCompiledCodeRef(firstPinned);

            firstPinned.undefine();
            assertTrue(GlobalVariable.existsGlobalCodeRefAsScalar(name).getBoolean());
            assertFalse(GlobalVariable.definedGlobalCodeRefAsScalar(name).getBoolean());
            RuntimeCode undefinedCode = (RuntimeCode) firstPinned.value;
            assertTrue(undefinedCode.isDeclared);
            assertEquals("Phase8bLowLevel", undefinedCode.packageName);
            assertEquals("same_name", undefinedCode.subName);

            firstPinned.set(firstCode);
            GlobalVariable.removeGlobalCodeRefForStashDelete(name);

            assertSame(firstPinned, GlobalVariable.getGlobalCodeRef(name));
            assertEquals("$", ((RuntimeCode) firstPinned.value).prototype);
        }

        try (PerlRuntime.Binding ignored = second.bind()) {
            assertFalse(GlobalVariable.existsGlobalCodeRef(name));
            assertFalse(GlobalVariable.isSubs.getOrDefault(name, false));
            assertFalse(GlobalVariable.globalGlobs.getOrDefault(name, false));
            assertFalse(GlobalVariable.getCompiledCodeRef(firstCompiledId).getDefinedBoolean());

            RuntimeScalar secondPinned = GlobalVariable.getGlobalCodeRef(name);
            secondPinned.set(secondCode);
            GlobalVariable.isSubs.put(name, true);
            GlobalVariable.globalGlobs.put(name, true);
            int secondCompiledId = GlobalVariable.registerCompiledCodeRef(secondPinned);

            assertEquals(firstCompiledId, secondCompiledId,
                    "compiled-code identifiers are allocated by each runtime");
            assertSame(secondPinned, GlobalVariable.getCompiledCodeRef(secondCompiledId));
            assertEquals("$$", ((RuntimeCode) secondPinned.value).prototype);
            assertNotSame(firstPinned, secondPinned);
        }

        try (PerlRuntime.Binding ignored = first.bind()) {
            assertSame(firstPinned, GlobalVariable.getCompiledCodeRef(firstCompiledId));
            assertTrue(GlobalVariable.isSubs.getOrDefault(name, false));
            assertTrue(GlobalVariable.globalGlobs.getOrDefault(name, false));
            assertEquals("$", ((RuntimeCode) GlobalVariable.getGlobalCodeRef(name).value).prototype);
        }
    }

    @Test
    void conflictingNamedSubsAndRedefinitionStayIsolatedOnJvmAndInterpreter() throws Exception {
        for (boolean interpreter : new boolean[]{false, true}) {
            PerlRuntime first = new PerlRuntime();
            PerlRuntime second = new PerlRuntime();
            String pkg = interpreter ? "Phase8bInterpreter" : "Phase8bJvm";

            run(first, "package " + pkg
                    + "; sub value ($) { 'first:' . $_[0] }"
                    + " sub method { 'first-method' } 1", interpreter);
            run(second, "package " + pkg
                    + "; sub value ($$) { 'second:' . $_[0] . ':' . $_[1] }"
                    + " sub method { 'second-method' } 1", interpreter);

            assertEquals("first:x:$:first-method", run(first,
                    pkg + "::value('x') . ':' . prototype('" + pkg + "::value')"
                            + " . ':' . " + pkg + "->method", interpreter));
            assertEquals("second:y:z:$$:second-method", run(second,
                    pkg + "::value('y', 'z') . ':' . prototype('" + pkg + "::value')"
                            + " . ':' . " + pkg + "->method", interpreter));

            run(first, "package " + pkg + "; no warnings qw(redefine prototype);"
                    + " sub value () { 'first-redefined' }"
                    + " sub method { 'first-method-redefined' } 1", interpreter);

            assertEquals("first-redefined::first-method-redefined", run(first,
                    pkg + "::value() . ':' . prototype('" + pkg + "::value')"
                            + " . ':' . " + pkg + "->method", interpreter));
            assertEquals("second:y:z:$$:second-method", run(second,
                    pkg + "::value('y', 'z') . ':' . prototype('" + pkg + "::value')"
                            + " . ':' . " + pkg + "->method", interpreter));
        }
    }

    private static RuntimeScalar codeScalar(String value, String prototype, String fullName) {
        RuntimeCode code = new RuntimeCode(
                (args, context) -> new RuntimeScalar(value).getList(), prototype);
        int separator = fullName.lastIndexOf("::");
        code.packageName = fullName.substring(0, separator);
        code.subName = fullName.substring(separator + 2);
        return new RuntimeScalar(code);
    }

    private static String run(PerlRuntime runtime, String source, boolean interpreter)
            throws Exception {
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            CompilerOptions options = new CompilerOptions();
            options.fileName = "<global-code-runtime-isolation>";
            options.code = source;
            options.useInterpreter = interpreter;
            return PerlLanguageProvider.executePerlCode(options, false).scalar().toString();
        }
    }
}
