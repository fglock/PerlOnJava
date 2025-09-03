package org.perlonjava.regex;

import org.perlonjava.runtime.PerlCompilerException;

import java.util.HashMap;
import java.util.Map;

public class CharacterClassMapper {
    private static final Map<String, String> CHARACTER_CLASSES = new HashMap<>();

    static {
        String[][] characterClasses = {
                {"[:ascii:]", "\\p{ASCII}"},
                {"[:^ascii:]", "\\P{ASCII}"},
                {"[:alpha:]", "\\p{Alpha}"},
                {"[:^alpha:]", "\\P{Alpha}"},
                {"[:alnum:]", "\\p{Alnum}"},
                {"[:^alnum:]", "\\P{Alnum}"},
                {"[:blank:]", "\\p{Blank}"},
                {"[:^blank:]", "\\P{Blank}"},
                {"[:cntrl:]", "\\p{Cntrl}"},
                {"[:^cntrl:]", "\\P{Cntrl}"},
                {"[:digit:]", "\\p{Digit}"},
                {"[:^digit:]", "\\P{Digit}"},
                {"[:graph:]", "\\p{Graph}"},
                {"[:^graph:]", "\\P{Graph}"},
                {"[:lower:]", "\\p{Lower}"},
                {"[:^lower:]", "\\P{Lower}"},
                {"[:print:]", "\\p{Print}"},
                {"[:^print:]", "\\P{Print}"},
                {"[:punct:]", "\\p{Punct}"},
                {"[:^punct:]", "\\P{Punct}"},
                {"[:space:]", "\\p{Space}"},
                {"[:^space:]", "\\P{Space}"},
                {"[:upper:]", "\\p{Upper}"},
                {"[:^upper:]", "\\P{Upper}"},
                {"[:word:]", "\\p{Alnum}_"},
                {"[:^word:]", "\\P{Alnum}_"},
                {"[:xdigit:]", "\\p{XDigit}"},
                {"[:^xdigit:]", "\\P{XDigit}"}
        };
        for (String[] characterClass : characterClasses) {
            CHARACTER_CLASSES.put(characterClass[0], characterClass[1]);
        }
    }

    public static String getMappedClass(String className) {
        String replacement = CHARACTER_CLASSES.get(className);
        if (replacement == null) {
            throw new PerlCompilerException("POSIX class " + className + " unknown");
        }
        return replacement;
    }
}
