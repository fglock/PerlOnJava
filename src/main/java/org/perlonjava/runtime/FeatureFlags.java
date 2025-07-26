package org.perlonjava.runtime;

import org.perlonjava.symbols.ScopedSymbolTable;

import java.util.*;

import static org.perlonjava.parser.SpecialBlockParser.getCurrentScope;

/**
 * A class to control lexical feature flags based on a hierarchy of bundles.
 */
public class FeatureFlags {
    // A hierarchy of feature bundles
    private static final Map<String, String[]> featureBundles = new HashMap<>();

    // Instance-level enabled features tracking
    private final Set<String> enabledFeatures = new HashSet<>();

    static {
        // Initialize the hierarchy of feature bundles
        featureBundles.put(":default", new String[]{"indirect", "multidimensional", "bareword_filehandles", "apostrophe_as_package_separator", "smartmatch"});
        featureBundles.put(":5.10", new String[]{"bareword_filehandles", "indirect", "multidimensional", "say", "state", "switch"});
        featureBundles.put(":5.12", new String[]{"bareword_filehandles", "indirect", "multidimensional", "say", "state", "switch", "unicode_strings"});
        featureBundles.put(":5.14", new String[]{"bareword_filehandles", "indirect", "multidimensional", "say", "state", "switch", "unicode_strings"});
        featureBundles.put(":5.16", new String[]{"bareword_filehandles", "current_sub", "evalbytes", "fc", "indirect", "multidimensional", "say", "state", "switch", "unicode_eval", "unicode_strings"});
        featureBundles.put(":5.18", new String[]{"bareword_filehandles", "current_sub", "evalbytes", "fc", "indirect", "multidimensional", "say", "state", "switch", "unicode_eval", "unicode_strings"});
        featureBundles.put(":5.20", new String[]{"bareword_filehandles", "current_sub", "evalbytes", "fc", "indirect", "multidimensional", "say", "state", "switch", "unicode_eval", "unicode_strings"});
        featureBundles.put(":5.22", new String[]{"bareword_filehandles", "current_sub", "evalbytes", "fc", "indirect", "multidimensional", "say", "state", "switch", "unicode_eval", "unicode_strings"});
        featureBundles.put(":5.24", new String[]{"bareword_filehandles", "current_sub", "evalbytes", "fc", "indirect", "multidimensional", "postderef_qq", "say", "state", "switch", "unicode_eval", "unicode_strings"});
        featureBundles.put(":5.26", new String[]{"bareword_filehandles", "current_sub", "evalbytes", "fc", "indirect", "multidimensional", "postderef_qq", "say", "state", "switch", "unicode_eval", "unicode_strings"});
        featureBundles.put(":5.28", new String[]{"bareword_filehandles", "bitwise", "current_sub", "evalbytes", "fc", "indirect", "multidimensional", "postderef_qq", "say", "state", "switch", "unicode_eval", "unicode_strings"});
        featureBundles.put(":5.30", new String[]{"bareword_filehandles", "bitwise", "current_sub", "evalbytes", "fc", "indirect", "multidimensional", "postderef_qq", "say", "state", "switch", "unicode_eval", "unicode_strings"});
        featureBundles.put(":5.32", new String[]{"bareword_filehandles", "bitwise", "current_sub", "evalbytes", "fc", "indirect", "multidimensional", "postderef_qq", "say", "state", "switch", "unicode_eval", "unicode_strings"});
        featureBundles.put(":5.34", new String[]{"bareword_filehandles", "bitwise", "current_sub", "evalbytes", "fc", "indirect", "multidimensional", "postderef_qq", "say", "state", "switch", "unicode_eval", "unicode_strings"});
        featureBundles.put(":5.36", new String[]{"bareword_filehandles", "bitwise", "current_sub", "evalbytes", "fc", "isa", "postderef_qq", "say", "signatures", "state", "unicode_eval", "unicode_strings"});
        featureBundles.put(":5.38", new String[]{"bitwise", "current_sub", "evalbytes", "fc", "isa", "module_true", "postderef_qq", "say", "signatures", "state", "unicode_eval", "unicode_strings"});
        featureBundles.put(":5.40", new String[]{"bitwise", "current_sub", "evalbytes", "fc", "isa", "module_true", "postderef_qq", "say", "signatures", "state", "try", "unicode_eval", "unicode_strings"});
        featureBundles.put(":5.42", new String[]{"bitwise", "current_sub", "evalbytes", "fc", "isa", "module_true", "postderef_qq", "say", "signatures", "state", "try", "unicode_eval", "unicode_strings"});
        // Not bundled:
        featureBundles.put("postderef", new String[]{"postderef"});
        featureBundles.put("keyword_all", new String[]{"keyword_all"});
        featureBundles.put("keyword_any", new String[]{"keyword_any"});
        featureBundles.put("lexical_subs", new String[]{"lexical_subs"});
        featureBundles.put("refaliasing", new String[]{"refaliasing"});
        featureBundles.put("declared_refs", new String[]{"declared_refs"});
        featureBundles.put("defer", new String[]{"defer"});
    }

    /**
     * Constructs a FeatureFlags object.
     */
    public FeatureFlags() {
    }

    /**
     * Returns a list of all feature names.
     *
     * @return A list of all feature names.
     */
    public static List<String> getFeatureList() {
        Set<String> featureSet = new HashSet<>();
        for (Map.Entry<String, String[]> entry : featureBundles.entrySet()) {
            featureSet.addAll(Arrays.asList(entry.getValue()));
        }
        featureSet.add("class");
        return new ArrayList<>(featureSet);
    }

    /**
     * Returns a list of all bundle names.
     *
     * @return A list of all bundle names.
     */
    public static List<String> getBundleList() {
        return new ArrayList<>(featureBundles.keySet());
    }

    /**
     * Checks if a feature exists.
     *
     * @param feature The name of the feature to check.
     * @return True if the feature exists, false otherwise.
     */
    public static boolean featureExists(String feature) {
        if (feature == null) {
            return false;
        }
        return getFeatureList().contains(feature.trim());
    }

    /**
     * Checks if a bundle exists.
     *
     * @param bundle The name of the bundle to check.
     * @return True if the bundle exists, false otherwise.
     */
    public static boolean bundleExists(String bundle) {
        if (bundle == null) {
            return false;
        }
        return featureBundles.containsKey(bundle.trim());
    }

    /**
     * Gets the features in a specific bundle.
     *
     * @param bundle The name of the bundle.
     * @return Array of feature names in the bundle, or null if bundle doesn't exist.
     */
    public static String[] getBundleFeatures(String bundle) {
        if (bundle == null) {
            return null;
        }
        return featureBundles.get(bundle.trim());
    }

    /**
     * Initialize with default features enabled.
     */
    public void initializeEnabledFeatures() {
        // Enable default features
        enableFeatureBundle(":default");
    }

    /**
     * Enables a feature bundle and its features.
     *
     * @param bundle The name of the feature bundle to enable.
     */
    public void enableFeatureBundle(String bundle) {
        setFeatureState(bundle, true);
    }

    /**
     * Disables a feature bundle and its features.
     *
     * @param bundle The name of the feature bundle to disable.
     */
    public void disableFeatureBundle(String bundle) {
        setFeatureState(bundle, false);
    }

    /**
     * Enables a single feature.
     *
     * @param feature The name of the feature to enable.
     */
    public void enableFeature(String feature) {
        if (feature != null && featureExists(feature)) {
            enabledFeatures.add(feature.trim());

            // Also try to enable it in the symbol table if available
            try {
                ScopedSymbolTable symbolTable = getCurrentScope();
                if (symbolTable != null) {
                    symbolTable.enableFeatureCategory(feature.trim());
                }
            } catch (Exception e) {
                // Ignore if symbol table operations fail
            }
        }
    }

    /**
     * Disables a single feature.
     *
     * @param feature The name of the feature to disable.
     */
    public void disableFeature(String feature) {
        if (feature != null) {
            enabledFeatures.remove(feature.trim());

            // Also try to disable it in the symbol table if available
            try {
                ScopedSymbolTable symbolTable = getCurrentScope();
                if (symbolTable != null) {
                    symbolTable.disableFeatureCategory(feature.trim());
                }
            } catch (Exception e) {
                // Ignore if symbol table operations fail
            }
        }
    }

    /**
     * Sets the state of a feature bundle and its features.
     *
     * @param bundle The name of the feature bundle.
     * @param state  The state to set (true for enabled, false for disabled).
     */
    private void setFeatureState(String bundle, boolean state) {
        if (bundle == null) {
            return;
        }

        String trimmedBundle = bundle.trim();

        if (featureBundles.containsKey(trimmedBundle)) {
            // It's a bundle - enable/disable all features in the bundle
            String[] features = featureBundles.get(trimmedBundle);
            for (String feature : features) {
                if (state) {
                    enableFeature(feature);
                } else {
                    disableFeature(feature);
                }
            }
        } else if (featureExists(trimmedBundle)) {
            // It's a single feature
            if (state) {
                enableFeature(trimmedBundle);
            } else {
                disableFeature(trimmedBundle);
            }
        }
    }

    /**
     * Checks if a feature is enabled.
     *
     * @param feature The name of the feature to check.
     * @return True if the feature is enabled, false otherwise.
     */
    public boolean isFeatureEnabled(String feature) {
        if (feature == null) {
            return false;
        }

        String trimmedFeature = feature.trim();

        // First check our local tracking
        if (enabledFeatures.contains(trimmedFeature)) {
            return true;
        }

        // Also check the symbol table if available
        try {
            ScopedSymbolTable symbolTable = getCurrentScope();
            if (symbolTable != null) {
                return symbolTable.isFeatureCategoryEnabled(trimmedFeature);
            }
        } catch (Exception e) {
            // Ignore if symbol table operations fail
        }

        return false;
    }

    /**
     * Gets all currently enabled features.
     *
     * @return Set of enabled feature names.
     */
    public Set<String> getEnabledFeatures() {
        return new HashSet<>(enabledFeatures);
    }

    /**
     * Clears all enabled features.
     */
    public void clearAllFeatures() {
        enabledFeatures.clear();

        // Also try to clear from symbol table if available
        try {
            ScopedSymbolTable symbolTable = getCurrentScope();
            if (symbolTable != null) {
                for (String feature : getFeatureList()) {
                    symbolTable.disableFeatureCategory(feature);
                }
            }
        } catch (Exception e) {
            // Ignore if symbol table operations fail
        }
    }

    /**
     * Returns a string representation of the current feature state.
     *
     * @return String showing enabled features.
     */
    @Override
    public String toString() {
        return "FeatureFlags{enabledFeatures=" + enabledFeatures + "}";
    }
}