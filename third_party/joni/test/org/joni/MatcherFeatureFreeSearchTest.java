package org.joni;

import static org.junit.Assert.assertEquals;

import org.jcodings.specific.UTF8Encoding;
import org.junit.Test;

/** Direct-engine coverage for the feature-free search entry point. */
public class MatcherFeatureFreeSearchTest {
    private static Regex regex(String source) {
        byte[] bytes = source.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new Regex(bytes, 0, bytes.length, Option.NONE, UTF8Encoding.INSTANCE);
    }

    private static int ordinary(Regex regex, byte[] input, int start, int range) {
        return regex.matcher(input).search(start, range, Option.NONE);
    }

    private static int featureFree(Regex regex, byte[] input, int start, int range) {
        return regex.matcher(input).searchWithoutControlVerbs(start, range, Option.NONE);
    }

    @Test
    public void directSearchMatchesOrdinarySearchForPlainAlternation() {
        Regex regex = regex("(?:42|gamma|epsilon)");
        byte[] input = "alpha:gamma:epsilon".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertEquals(ordinary(regex, input, 0, input.length),
                featureFree(regex, input, 0, input.length));
        assertEquals(ordinary(regex, input, 7, input.length),
                featureFree(regex, input, 7, input.length));
    }

    @Test
    public void directSearchRetainsExplicitGlobalPosition() {
        Regex regex = regex("gamma");
        byte[] input = "alpha:gamma".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertEquals(regex.matcher(input).search(6, 6, input.length, Option.NONE),
                regex.matcher(input).searchWithoutControlVerbs(6, 6, input.length, Option.NONE));
    }
}
