package org.perlonjava.runtime.regex;

import org.perlonjava.runtime.NamedCharacterExpansionMap;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

/** A pattern scalar carrying immutable lexical regex metadata from its CV. */
public final class RuntimeRegexLiteralScalar extends RuntimeScalar {
    private final NamedCharacterExpansionMap namedCharacterExpansions;

    RuntimeRegexLiteralScalar(
            RuntimeScalar pattern, NamedCharacterExpansionMap namedCharacterExpansions) {
        super(pattern);
        this.namedCharacterExpansions = namedCharacterExpansions;
    }

    public NamedCharacterExpansionMap namedCharacterExpansions() {
        return namedCharacterExpansions;
    }
}
