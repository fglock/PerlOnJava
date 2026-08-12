package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

/** Java implementation of String::Similarity's fstrcmp XS entry point. */
public class StringSimilarity extends PerlModuleBase {

    public static final String XS_VERSION = "1.04";

    public StringSimilarity() {
        super("String::Similarity", false);
    }

    public static void initialize() {
        StringSimilarity module = new StringSimilarity();
        try {
            module.registerMethod("fstrcmp", "@");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Unable to initialize String::Similarity", e);
        }
    }

    public static RuntimeList fstrcmp(RuntimeArray args, int ctx) {
        String left = args.isEmpty() ? "" : args.get(0).toString();
        String right = args.size() < 2 ? "" : args.get(1).toString();
        int[] a = left.codePoints().toArray();
        int[] b = right.codePoints().toArray();

        if (a.length == 0 && b.length == 0) {
            return new RuntimeScalar(1.0).getList();
        }

        // fstrcmp's score is the common subsequence mass divided by the two
        // input lengths: 2 * LCS / (len(a) + len(b)).  Use two rows so memory
        // remains bounded for the long POD strings AnnoCPAN compares.
        if (b.length > a.length) {
            int[] swap = a;
            a = b;
            b = swap;
        }
        int[] previous = new int[b.length + 1];
        int[] current = new int[b.length + 1];
        for (int leftCodePoint : a) {
            for (int j = 1; j <= b.length; j++) {
                current[j] = leftCodePoint == b[j - 1]
                        ? previous[j - 1] + 1
                        : Math.max(previous[j], current[j - 1]);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
            java.util.Arrays.fill(current, 0);
        }
        double score = (2.0 * previous[b.length]) / (a.length + b.length);
        return new RuntimeScalar(score).getList();
    }
}
