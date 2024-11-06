package org.perlonjava.runtime;

import java.util.HashMap;
import java.util.Map;

/**
 * A class to control lexical feature flags based on a hierarchy of bundles.
 */
public class Feature {
    // A hierarchy of feature bundles
    private static final Map<String, String[]> featureBundles = new HashMap<>();

    static {
        // Initialize the hierarchy of feature bundles
        featureBundles.put(":default", new String[]{"indirect", "multidimensional", "bareword_filehandles"});
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
    }

    private final ScopedSymbolTable symbolTable;

    /**
     * Constructs a Feature object associated with a ScopedSymbolTable.
     *
     * @param symbolTable The ScopedSymbolTable to manage features for.
     */
    public Feature(ScopedSymbolTable symbolTable) {
        this.symbolTable = symbolTable;
    }

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
     * Sets the state of a feature bundle and its features.
     *
     * @param bundle The name of the feature bundle.
     * @param state  The state to set (true for enabled, false for disabled).
     */
    private void setFeatureState(String bundle, boolean state) {
        if (featureBundles.containsKey(bundle)) {
            for (String feature : featureBundles.get(bundle)) {
                if (state) {
                    symbolTable.enableFeatureCategory(feature);
                } else {
                    symbolTable.disableFeatureCategory(feature);
                }
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
        return symbolTable.isFeatureCategoryEnabled(feature);
    }
}
