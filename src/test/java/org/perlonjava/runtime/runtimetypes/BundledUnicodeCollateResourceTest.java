package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ensures Unicode::Collate can load its bundled DUCET tables through @INC. */
@Tag("unit")
class BundledUnicodeCollateResourceTest {

    @Test
    void exposesDucetTablesThroughJarPerlLib() {
        assertTrue(Jar.exists("jar:PERL5LIB/Unicode/Collate/allkeys.txt"),
                "Unicode::Collate allkeys.txt must be packaged in the runtime JAR");
        assertTrue(Jar.exists("jar:PERL5LIB/Unicode/Collate/keys.txt"),
                "Unicode::Collate keys.txt must be packaged in the runtime JAR");
    }
}
