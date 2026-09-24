package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class ActiveLexicalRegistrationTest {

    @Test
    void registersALogicalCodeProxyInTheActiveFrame() {
        PerlRuntime runtime = new PerlRuntime();
        RuntimeCode active = new RuntimeCode("active", java.util.List.of());
        RuntimeCode proxy = new RuntimeCode("proxy", java.util.List.of());
        proxy.__SUB__ = new RuntimeScalar(active);
        RuntimeScalar cell = new RuntimeScalar("value");

        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode.pushActiveCode(active);
            proxy.resolveLexicalAlias("$value", cell);

            assertSame(cell, RuntimeCode.snapshotActiveLexicals(active).get("$value"));
            RuntimeCode.popActiveCode(active);
        }
    }

    @Test
    void retainsTheNonTopFrameLookupForNestedCalls() {
        PerlRuntime runtime = new PerlRuntime();
        RuntimeCode outer = new RuntimeCode("outer", java.util.List.of());
        RuntimeCode inner = new RuntimeCode("inner", java.util.List.of());
        RuntimeScalar cell = new RuntimeScalar("outer");

        try (PerlRuntime.Binding ignored = runtime.bind()) {
            RuntimeCode.pushActiveCode(outer);
            RuntimeCode.pushActiveCode(inner);
            outer.resolveLexicalAlias("$outer", cell);

            assertSame(cell, RuntimeCode.snapshotActiveLexicals(outer).get("$outer"));
            RuntimeCode.popActiveCode(inner);
            RuntimeCode.popActiveCode(outer);
        }
    }
}
