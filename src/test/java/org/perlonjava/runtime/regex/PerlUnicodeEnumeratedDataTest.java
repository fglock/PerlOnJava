package org.perlonjava.runtime.regex;

import static org.junit.jupiter.api.Assertions.*;
import com.ibm.icu.text.UnicodeSet;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PerlUnicodeEnumeratedDataTest {
    @Test void pinsUnicode17SourcesAndCounts() {
        assertEquals("17.0.0", PerlUnicodeEnumeratedData.UNICODE_VERSION);
        assertEquals("dadbaf38a0d0246e5b805bf8725cb81b7c621f93d030595635f5ba2c2f179428", PerlUnicodeEnumeratedData.BPT_SHA256);
        assertEquals("24c7fed1195c482faaefd5c1e7eb821c5ee1fb6de07ecdbaa64b56a99da22c08", PerlUnicodeEnumeratedData.INCB_SHA256);
        assertEquals("f39ebe974825d6736aee15582250307aa532b2cfab3caf3f86bd23fddc9c5c4d", PerlUnicodeEnumeratedData.JT_SHA256);
        assertEquals("7c83684d3336b698381745b78a971c3e1242cb3fcac58604469086c19b6edcee", PerlUnicodeEnumeratedData.NT_SHA256);
        assertEquals("dcef09c3fb24d356b042569c328ec341efc5b53447700d799f2fb4834c3cd3cd", PerlUnicodeEnumeratedData.VO_SHA256);
        assertEquals(128, PerlUnicodeEnumeratedData.BPT_EXPLICIT_RANGE_COUNT);
        assertEquals(505, PerlUnicodeEnumeratedData.INCB_EXPLICIT_RANGE_COUNT);
        assertEquals(542, PerlUnicodeEnumeratedData.JT_EXPLICIT_RANGE_COUNT);
        assertEquals(265, PerlUnicodeEnumeratedData.NT_EXPLICIT_RANGE_COUNT);
        assertEquals(2470, PerlUnicodeEnumeratedData.VO_EXPLICIT_RANGE_COUNT);
    }

    @Test void resolvesExactLooseAndPropertySpecificAliases() {
        assertTrue(PerlUnicodeEnumeratedData.isPropertyAlias("Bidi Paired-Bracket_Type"));
        assertTrue(PerlUnicodeEnumeratedData.isPropertyAlias("InCB"));
        assertTrue(PerlUnicodeEnumeratedData.isPropertyAlias("joining type"));
        assertEquals("c", PerlUnicodeEnumeratedData.shortValue("bpt", "Close"));
        assertEquals("Non_Joining", PerlUnicodeEnumeratedData.canonicalValue("jt", "U"));
        assertEquals("Decimal", PerlUnicodeEnumeratedData.canonicalValue("nt", "De"));
        assertEquals("Transformed_Rotated", PerlUnicodeEnumeratedData.canonicalValue("vo", "Tr"));
        assertNull(PerlUnicodeEnumeratedData.valueSet("nt", "Open"));
        assertNull(PerlUnicodeEnumeratedData.valueSet("unknown", "None"));
    }

    @Test void mapsRepresentativeExplicitAndDefaultValues() {
        assertTrue(PerlUnicodeEnumeratedData.valueSet("bpt", "Open").contains('('));
        assertTrue(PerlUnicodeEnumeratedData.valueSet("bpt", "Close").contains(')'));
        assertTrue(PerlUnicodeEnumeratedData.valueSet("bpt", "None").contains('A'));
        assertTrue(PerlUnicodeEnumeratedData.valueSet("InCB", "Linker").contains(0x094D));
        assertTrue(PerlUnicodeEnumeratedData.valueSet("InCB", "Consonant").contains(0x0915));
        assertTrue(PerlUnicodeEnumeratedData.valueSet("InCB", "Extend").contains(0x0300));
        assertTrue(PerlUnicodeEnumeratedData.valueSet("jt", "C").contains(0x0640));
        assertTrue(PerlUnicodeEnumeratedData.valueSet("jt", "D").contains(0x0620));
        assertTrue(PerlUnicodeEnumeratedData.valueSet("jt", "L").contains(0xA872));
        assertTrue(PerlUnicodeEnumeratedData.valueSet("jt", "R").contains(0x0622));
        assertTrue(PerlUnicodeEnumeratedData.valueSet("jt", "T").contains(0x00AD));
        assertTrue(PerlUnicodeEnumeratedData.valueSet("nt", "Decimal").contains('0'));
        assertTrue(PerlUnicodeEnumeratedData.valueSet("nt", "Digit").contains(0x00B2));
        assertTrue(PerlUnicodeEnumeratedData.valueSet("nt", "Numeric").contains(0x00BC));
        assertTrue(PerlUnicodeEnumeratedData.valueSet("vo", "U").contains(0x00A7));
        assertTrue(PerlUnicodeEnumeratedData.valueSet("vo", "Tr").contains(0x3014));
        assertTrue(PerlUnicodeEnumeratedData.valueSet("vo", "Tu").contains(0x3001));
        assertTrue(PerlUnicodeEnumeratedData.valueSet("vo", "R").contains('A'));
    }

    @Test void retainsEveryValueWildcardAndCompleteDisjointPartitions() {
        String[] properties={"bpt","InCB","jt","nt","vo"}; int[] counts={3,4,6,4,4}; int[] wildcards={6,4,12,7,8};
        for(int p=0;p<properties.length;p++) {
            String[] values=PerlUnicodeEnumeratedData.canonicalValues(properties[p]);
            assertEquals(counts[p],values.length); assertEquals(wildcards[p],PerlUnicodeEnumeratedData.wildcardValues(properties[p]).length);
            UnicodeSet union=new UnicodeSet();
            for(int i=0;i<values.length;i++) { UnicodeSet set=PerlUnicodeEnumeratedData.valueSet(properties[p],values[i]); assertTrue(set.isFrozen()); union.addAll(set); for(int j=i+1;j<values.length;j++) assertTrue(new UnicodeSet(set).retainAll(PerlUnicodeEnumeratedData.valueSet(properties[p],values[j])).isEmpty()); }
            assertEquals(0x110000,union.size()); assertTrue(union.contains(0,0x10FFFF));
            for(String wildcard:PerlUnicodeEnumeratedData.wildcardValues(properties[p])) assertNotNull(PerlUnicodeEnumeratedData.valueSet(properties[p],wildcard));
        }
    }
}
