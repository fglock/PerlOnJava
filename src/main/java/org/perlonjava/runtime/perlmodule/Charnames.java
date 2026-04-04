package org.perlonjava.runtime.perlmodule;

import com.ibm.icu.lang.UCharacter;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;

/**
 * Java-side implementation for _charnames module.
 * Provides Unicode character name lookup via ICU4J.
 */
public class Charnames extends PerlModuleBase {

    public Charnames() {
        super("_charnames", false);  // Don't set %INC - let the Perl _charnames.pm load normally
    }

    public static void initialize() {
        Charnames charnames = new Charnames();
        try {
            charnames.registerMethod("_java_viacode", "javaViacode", "$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing _charnames method: " + e.getMessage());
        }
    }

    /**
     * Returns the Unicode character name for a given code point.
     * Uses ICU4J's UCharacter.getName() which provides full Unicode name data.
     *
     * @param args Code point as integer
     * @param ctx  Context
     * @return Character name string, or undef if not found
     */
    public static RuntimeList javaViacode(RuntimeArray args, int ctx) {
        int codePoint = args.getFirst().getInt();
        String name = UCharacter.getName(codePoint);
        if (name == null || name.isEmpty()) {
            return new RuntimeList(scalarUndef);
        }
        return new RuntimeList(new RuntimeScalar(name));
    }
}
