package org.perlonjava.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;
import org.perlonjava.runtime.runtimetypes.GlobalContext;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeHash;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.runtime.runtimetypes.RuntimeScalarType;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class NamedCharacterExpansionTest {

    @Test
    void immutableResultCoversStandardEmptyAndMultiCharacterExpansions() {
        NamedCharacterExpansion standard = NamedCharacterExpansion.resolve(
                "LATIN CAPITAL LETTER A", null,
                NamedCharacterExpansion.SourceMode.BYTE);
        assertTrue(standard.resolved());
        assertEquals("A", standard.sequence());
        assertEquals(NamedCharacterExpansion.SourceMode.UNICODE, standard.sourceMode());
        assertTrue(standard.promotesUnicode());

        AtomicInteger calls = new AtomicInteger();
        RuntimeCode callback = new RuntimeCode((args, context) -> {
            calls.incrementAndGet();
            String name = args.get(0).toString();
            return new RuntimeList(new RuntimeScalar(
                    name.equals("EMPTY-STR") ? "" : "WARN"));
        }, null);
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            NamedCharacterExpansion empty = NamedCharacterExpansion.resolve(
                    "EMPTY-STR", new RuntimeScalar(callback),
                    NamedCharacterExpansion.SourceMode.BYTE);
            assertTrue(empty.resolved());
            assertTrue(empty.empty());
            assertEquals(NamedCharacterExpansion.SourceMode.UNICODE, empty.sourceMode());
            assertEquals(1, calls.get());

            NamedCharacterExpansion sequence = NamedCharacterExpansion.resolve(
                    "LONG-STR", new RuntimeScalar(callback),
                    NamedCharacterExpansion.SourceMode.UNICODE);
            assertEquals("WARN", sequence.sequence());
            assertEquals(2, calls.get());

            NamedCharacterExpansion uPlus = NamedCharacterExpansion.resolve(
                    "U+0100", new RuntimeScalar(callback),
                    NamedCharacterExpansion.SourceMode.BYTE);
            assertEquals("\u0100", uPlus.sequence());
            assertEquals(2, calls.get());
        }
    }

    @Test
    void evalStringUsesTheScalarLexicalHintSnapshot() {
        AtomicInteger calls = new AtomicInteger();
        RuntimeCode callback = new RuntimeCode((args, context) -> {
            calls.incrementAndGet();
            return new RuntimeList(new RuntimeScalar(args.get(0).toString()));
        }, null);
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeHash hints = GlobalVariable.getGlobalHash(GlobalContext.encodeSpecialVar("H"));
            hints.elements.put("charnames", new RuntimeScalar(callback));
            int snapshotId = HintHashRegistry.snapshotCurrentHintHash();
            HintHashRegistry.setCallSiteHintHashId(snapshotId);
            HintHashRegistry.setCallSiteHintHashId(0);
            HintHashRegistry.setCallSiteHintHashId(snapshotId);
            hints.elements.put("charnames", new RuntimeScalar(new RuntimeScalar(callback).toString()));

            NamedCharacterExpansion result = NamedCharacterExpansion.resolve(
                    "Hidden Name", NamedCharacterExpansion.SourceMode.UNICODE);
            assertEquals("Hidden Name", result.sequence());
            assertEquals(1, calls.get());

            hints.elements.put("charnames", new RuntimeScalar("different lexical hint"));
            NamedCharacterExpansion mismatch = NamedCharacterExpansion.resolve(
                    "Hidden Name", NamedCharacterExpansion.SourceMode.UNICODE);
            assertEquals(NamedCharacterExpansion.Status.UNRESOLVED, mismatch.status());
        }
    }

    @Test
    void byteInputAndCallbackOutputProvenanceArePreserved() {
        AtomicInteger argumentType = new AtomicInteger(-1);
        RuntimeCode callback = new RuntimeCode((args, context) -> {
            argumentType.set(args.get(0).type);
            return new RuntimeList(new RuntimeScalar(
                    new byte[] {(byte) 0xdf}));
        }, null);
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            NamedCharacterExpansion result = NamedCharacterExpansion.resolve(
                    "foo\u00df", new RuntimeScalar(callback),
                    NamedCharacterExpansion.SourceMode.BYTE);
            assertEquals(RuntimeScalarType.BYTE_STRING, argumentType.get());
            assertEquals("\u00df", result.sequence());
            assertEquals(NamedCharacterExpansion.SourceMode.BYTE, result.sourceMode());
        }
    }

    @Test
    void validationMatchesPerlCharnamePolicy() {
        assertTrue(NamedCharacterExpansion.validationError("TOO  MANY SPACES")
                .contains("multiple spaces"));
        assertTrue(NamedCharacterExpansion.validationError("COM,MA")
                .contains("Invalid character"));
        assertTrue(NamedCharacterExpansion.validationError("A\u00d7O")
                .contains("Invalid character"));
        assertTrue(NamedCharacterExpansion.validationError("\u0664 HORSEMEN")
                .contains("Invalid character"));
    }
}
