package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;

@Tag("unit")
class RuntimeRegexBackendInvarianceTest {
    @Test
    void retiredJavaPropertyCannotChangeTheProductionEngine() {
        String original = System.getProperty("jperl.regex.backend");
        System.setProperty("jperl.regex.backend", "java");
        try (PerlRuntime.Binding ignored = new PerlRuntime().bind()) {
            RuntimeRegex regex = RuntimeRegex.compile("ordinary", "");
            assertNotNull(regex.recursivePattern,
                    "ordinary production patterns must compile through Joni");
        } finally {
            if (original == null) {
                System.clearProperty("jperl.regex.backend");
            } else {
                System.setProperty("jperl.regex.backend", original);
            }
        }
    }
}
