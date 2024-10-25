package org.perlonjava.operators;

import com.ibm.icu.text.CaseMap;
import com.ibm.icu.text.Normalizer2;
import org.perlonjava.runtime.RuntimeScalar;

import static org.perlonjava.runtime.RuntimeScalarCache.getScalarInt;

/**
 * A utility class that provides various string operations on {@link RuntimeScalar} objects.
 */
public class StringOperators {

    /**
     * Returns the length of the string representation of the given {@link RuntimeScalar}.
     *
     * @param runtimeScalar the {@link RuntimeScalar} whose length is to be calculated
     * @return a {@link RuntimeScalar} containing the length of the input as an integer
     */
    public static RuntimeScalar length(RuntimeScalar runtimeScalar) {
        return getScalarInt(runtimeScalar.toString().length());
    }

    /**
     * Escapes all non-alphanumeric characters in the string representation of the given {@link RuntimeScalar}.
     *
     * @param runtimeScalar the {@link RuntimeScalar} to be quoted
     * @return a {@link RuntimeScalar} with non-alphanumeric characters escaped
     */
    public static RuntimeScalar quotemeta(RuntimeScalar runtimeScalar) {
        StringBuilder quoted = new StringBuilder();
        for (char c : runtimeScalar.value.toString().toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                quoted.append(c);
            } else {
                quoted.append("\\").append(c);
            }
        }
        return new RuntimeScalar(quoted.toString());
    }

    /**
     * Performs full Unicode case folding on the string representation of the given {@link RuntimeScalar}.
     *
     * @param runtimeScalar the {@link RuntimeScalar} to be case folded
     * @return a {@link RuntimeScalar} with the case-folded string
     */
    public static RuntimeScalar fc(RuntimeScalar runtimeScalar) {
        String str = runtimeScalar.toString();
        // Step 1: Normalize the string to NFKC form (Compatibility Composition)
        Normalizer2 normalizer = Normalizer2.getNFKCInstance();
        String normalized = normalizer.normalize(str);

        // Step 2: Perform full Unicode case folding using ICU4J CaseMap
        str = CaseMap.fold().apply(normalized);

        return new RuntimeScalar(str);
    }

    /**
     * Converts the string representation of the given {@link RuntimeScalar} to lowercase.
     *
     * @param runtimeScalar the {@link RuntimeScalar} to be converted to lowercase
     * @return a {@link RuntimeScalar} with the lowercase string
     */
    public static RuntimeScalar lc(RuntimeScalar runtimeScalar) {
        return new RuntimeScalar(runtimeScalar.toString().toLowerCase());
    }

    /**
     * Converts the first character of the string representation of the given {@link RuntimeScalar} to lowercase.
     *
     * @param runtimeScalar the {@link RuntimeScalar} whose first character is to be converted to lowercase
     * @return a {@link RuntimeScalar} with the first character in lowercase
     */
    public static RuntimeScalar lcfirst(RuntimeScalar runtimeScalar) {
        String str = runtimeScalar.toString();
        if (str.isEmpty()) {
            return new RuntimeScalar(str);
        }
        return new RuntimeScalar(str.substring(0, 1).toLowerCase() + str.substring(1));
    }

    /**
     * Converts the string representation of the given {@link RuntimeScalar} to uppercase.
     *
     * @param runtimeScalar the {@link RuntimeScalar} to be converted to uppercase
     * @return a {@link RuntimeScalar} with the uppercase string
     */
    public static RuntimeScalar uc(RuntimeScalar runtimeScalar) {
        return new RuntimeScalar(runtimeScalar.toString().toUpperCase());
    }

    /**
     * Converts the first character of the string representation of the given {@link RuntimeScalar} to uppercase.
     *
     * @param runtimeScalar the {@link RuntimeScalar} whose first character is to be converted to uppercase
     * @return a {@link RuntimeScalar} with the first character in uppercase
     */
    public static RuntimeScalar ucfirst(RuntimeScalar runtimeScalar) {
        String str = runtimeScalar.toString();
        if (str.isEmpty()) {
            return new RuntimeScalar(str);
        }
        return new RuntimeScalar(str.substring(0, 1).toUpperCase() + str.substring(1));
    }
}
