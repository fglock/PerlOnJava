import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class RuntimeRegex {

    private static final ConcurrentHashMap<String, Pattern> regexCache = new ConcurrentHashMap<>();

    public Pattern pattern;
    public boolean isGlobalMatch;
    public boolean isNonDestructive;

    // Method to convert modifiers into regex flags
    private int convertModifiers(String modifiers) {
        int flags = 0;
        if (modifiers.contains("i")) {
            flags |= Pattern.CASE_INSENSITIVE;
        }
        // Add other modifiers if necessary
        return flags;
    }

    /**
     * Creates a RuntimeRegex object from a regex pattern string with optional modifiers.
     *
     * @param patternString The regex pattern string with optional modifiers.
     * @param modifiers     Modifiers for the regex pattern (e.g., "i", "g").
     * @return A RuntimeRegex object.
     */
    public static RuntimeRegex compile(String patternString, String modifiers) {
        RuntimeRegex regex = new RuntimeRegex();
        try {
            int flags = regex.convertModifiers(modifiers);
            regex.isGlobalMatch = modifiers.contains("g");
            regex.isNonDestructive = modifiers.contains("r");

            // Create a unique cache key
            String cacheKey = patternString + "/" + modifiers;

            // Check the cache for the compiled pattern
            Pattern compiledPattern = regexCache.computeIfAbsent(cacheKey, key -> {
                return Pattern.compile(patternString, flags);
            });

            // Use the cached pattern
            regex.pattern = compiledPattern;
        } catch (Exception e) {
            throw new IllegalStateException("Regex compilation failed: " + e.getMessage());
        }
        return regex;
    }
}

