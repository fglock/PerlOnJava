package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

/**
 * Java replacement for Text::Levenshtein::XS 0.503's native XS entry point.
 * The bundled upstream Perl wrapper converts strings to Unicode code points
 * before calling this class, preserving the distribution's public API.
 *
 * <p>The original module is copyright (c) 2016 by Nick Logan and is
 * distributed under the same terms as Perl 5.</p>
 */
public final class TextLevenshteinXS extends PerlModuleBase {
    public static final String XS_VERSION = "0.503";
    private static final String MODULE = "Text::Levenshtein::XS";

    public TextLevenshteinXS() {
        super(MODULE, false);
    }

    public static void initialize() {
        TextLevenshteinXS module = new TextLevenshteinXS();
        try {
            module.registerMethod("xs_distance", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Unable to initialize " + MODULE, e);
        }
    }

    public static RuntimeList xs_distance(RuntimeArray args, int ctx) {
        RuntimeArray left = args.get(0).arrayDeref();
        RuntimeArray right = args.get(1).arrayDeref();
        int maxDistance = args.get(2).getInt();

        if (left.size() < right.size()) {
            RuntimeArray swap = left;
            left = right;
            right = swap;
        }

        if (maxDistance > 0 && left.size() - right.size() > maxDistance) {
            return new RuntimeScalar().getList();
        }

        int[] previous = new int[right.size() + 1];
        int[] current = new int[right.size() + 1];
        for (int j = 0; j <= right.size(); j++) {
            previous[j] = j;
        }

        for (int i = 1; i <= left.size(); i++) {
            current[0] = i;
            int leftCodePoint = left.get(i - 1).getInt();
            for (int j = 1; j <= right.size(); j++) {
                int substitutionCost = leftCodePoint == right.get(j - 1).getInt() ? 0 : 1;
                current[j] = Math.min(
                        Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + substitutionCost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }

        int distance = previous[right.size()];
        if (maxDistance > 0 && distance > maxDistance) {
            return new RuntimeScalar().getList();
        }
        return new RuntimeScalar(distance).getList();
    }
}
