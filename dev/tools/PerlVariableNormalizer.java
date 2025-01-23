import java.util.HashMap;
import java.util.Map;

public class PerlVariableNormalizer {

    // Cache to store previously normalized variables for faster lookup
    private static final Map<String, String> cache = new HashMap<>();

    public static String normalizeVariableName(String variable, String defaultPackage) {

        // Create a cache key based on both the variable and the default package
        String cacheKey = defaultPackage + "::" + variable;

        // Check the cache first
        if (cache.containsKey(cacheKey)) {
            return cache.get(cacheKey);
        }

        char sigil = variable.charAt(0);
        String name = variable.substring(1);
        if (!Character.isLetter(name.charAt(0))) {
            defaultPackage = "main";    // special variables are always in main
        }

        // Use StringBuilder for efficient string operations
        StringBuilder normalized = new StringBuilder(variable.length() + defaultPackage.length() + 2);

        // Special case handling
        if (name.startsWith("::")) {
            // $::x
            normalized.append(sigil).append(defaultPackage).append(name);
        } else if (name.contains("::")) {
            // If already in a package, return as-is
            normalized.append(variable);
        } else {
            // Prepend default package
            normalized.append(sigil).append(defaultPackage).append("::").append(name);
        }

        // Convert to string and store in cache
        String normalizedStr = normalized.toString();
        cache.put(cacheKey, normalizedStr);

        return normalizedStr;
    }

    public static void main(String[] args) {
        // Example variables to normalize
        String[] variables = {"@_", "$x", "%::", "$Package::Name::x", "$y", "%hash"};

        long startTime = System.nanoTime();

        String defaultPackage = "main";
        for (String variable : variables) {
            String normalized = normalizeVariableName(variable, defaultPackage);
            System.out.println("Original: " + variable + " -> Normalized: " + normalized);
        }

        defaultPackage = "My::Package";
        for (String variable : variables) {
            String normalized = normalizeVariableName(variable, defaultPackage);
            System.out.println("Original: " + variable + " -> Normalized: " + normalized);
        }

        long endTime = System.nanoTime();
        System.out.println("Normalization took " + (endTime - startTime) + " ns");
    }
}
