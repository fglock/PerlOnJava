package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@Tag("unit")
class RuntimeSubstrLvalueRefreshTest {
    @Test
    void positiveBoundedAliasRefreshesAfterParentMutation() {
        RuntimeScalar parent = new RuntimeScalar("abcdef");
        RuntimeSubstrLvalue alias = new RuntimeSubstrLvalue(parent, "bcd", 1, 3);

        parent.set(new RuntimeScalar("uvwxyz"));

        assertEquals("vwx", alias.toString());
    }

    @Test
    void positiveBoundedAliasClampsAtParentEnd() {
        RuntimeScalar parent = new RuntimeScalar("abc");
        RuntimeSubstrLvalue alias = new RuntimeSubstrLvalue(parent, "", 10, 4);

        assertEquals("", alias.toString());
    }

    @Test
    void unchangedParentReusesLiveSlice() {
        RuntimeScalar parent = new RuntimeScalar("abcdef");
        RuntimeSubstrLvalue alias = new RuntimeSubstrLvalue(parent, "bcd", 1, 3);

        String first = alias.toString();
        assertSame(first, alias.toString());
    }
}
