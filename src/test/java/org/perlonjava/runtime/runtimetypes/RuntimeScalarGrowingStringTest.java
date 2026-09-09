package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class RuntimeScalarGrowingStringTest {
    @Test
    void deferredAppendPreservesEveryPrefixAndSuffix() {
        RuntimeScalar scalar = new RuntimeScalar("prefix");
        scalar.appendGrowingString("-");
        scalar.appendGrowingString("suffix");

        assertEquals("prefix-suffix", scalar.toString());
    }

    @Test
    void transferableConcatMaterializesIntoDestination() {
        RuntimeScalar source = new RuntimeScalar("left");
        RuntimeScalar right = new RuntimeScalar("-right");
        RuntimeScalar temporary = source.appendedStringAssignmentResult(
                right.toString(), RuntimeScalarType.STRING, right);
        RuntimeScalar destination = new RuntimeScalar();
        destination.set(temporary);

        assertEquals("left-right", destination.toString());
    }
}
