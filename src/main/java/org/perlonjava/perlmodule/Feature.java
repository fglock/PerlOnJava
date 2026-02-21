package org.perlonjava.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;
import org.perlonjava.symbols.ScopedSymbolTable;

import static org.perlonjava.frontend.parser.SpecialBlockParser.getCurrentScope;
import static org.perlonjava.runtime.runtimetypes.FeatureFlags.getFeatureList;

/**
 * The FeatureFlags class provides functionalities similar to the Perl feature module.
 */
public class Feature extends PerlModuleBase {

    public static FeatureFlags featureManager = new FeatureFlags();

    /**
     * Constructor for FeatureFlags.
     * Initializes the module with the name "feature".
     * Pass false to not override Perl's package variables.
     */
    public Feature() {
        super("feature", false);
    }

    /**
     * Static initializer to set up the FeatureFlags module.
     */
    public static void initialize() {
        Feature feature = new Feature();
        try {
            feature.registerMethod("feature_enabled", "$;$");
            feature.registerMethod("features_enabled", ";$");
            feature.registerMethod("feature_bundle", ";$");
            feature.registerMethod("import", "useFeature", ";$");
            feature.registerMethod("unimport", "noFeature", ";$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Feature method: " + e.getMessage());
        }

        featureManager = new FeatureFlags();

        // Populate %feature::feature hash for experimental.pm compatibility
        // This makes the hash accessible from Perl code
        populateFeatureHash();
    }

    /**
     * Populates the %feature::feature hash so Perl code can access it.
     * This is needed for experimental.pm to work.
     */
    private static void populateFeatureHash() {
        try {
            // Create a new hash with all features
            RuntimeHash featureHash = new RuntimeHash();
            java.util.List<String> features = getFeatureList();
            for (String featureName : features) {
                featureHash.put(featureName, new RuntimeScalar("feature_" + featureName));
            }

            // Set the global variable to point to this hash
            RuntimeScalar hashRef = featureHash.createReference();
            GlobalVariable.getGlobalVariable("%feature::feature").set(hashRef.value);
        } catch (Exception e) {
            System.err.println("Warning: Could not populate %feature::feature hash: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Enables a feature bundle.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList useFeature(RuntimeArray args, int ctx) {
        ScopedSymbolTable symbolTable = getCurrentScope();

        if (args.size() == 1) {
            // No arguments - do nothing
            return new RuntimeScalar().getList();
        }

        for (int i = 1; i < args.size(); i++) {
            String featureName = args.get(i).toString();

            // enableFeatureBundle handles both bundles (":5.10") and individual features ("say")
            // It calls enableFeature() which updates both featureManager and symbolTable
            featureManager.enableFeatureBundle(featureName);
        }
        return new RuntimeScalar().getList();
    }

    /**
     * Disables all features or a specific feature bundle.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList noFeature(RuntimeArray args, int ctx) {
        ScopedSymbolTable symbolTable = getCurrentScope();

        if (args.size() == 1) {
            // No arguments - do nothing
            return new RuntimeScalar().getList();
        }

        for (int i = 1; i < args.size(); i++) {
            String featureName = args.get(i).toString();

            // disableFeatureBundle handles both bundles (":5.10") and individual features ("say")
            // It calls disableFeature() which updates both featureManager and symbolTable
            featureManager.disableFeatureBundle(featureName);
        }
        return new RuntimeScalar().getList();
    }

    /**
     * Checks if a feature is enabled.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing a boolean value.
     */
    public static RuntimeList feature_enabled(RuntimeArray args, int ctx) {
        if (args.size() < 1 || args.size() > 2) {
            throw new IllegalStateException("Bad number of arguments for feature_enabled()");
        }
        String feature = args.get(0).toString();
        boolean isEnabled = featureManager.isFeatureEnabled(feature);
        return new RuntimeScalar(isEnabled).getList();
    }

    /**
     * Returns a list of enabled features.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing enabled features.
     */
    public static RuntimeList features_enabled(RuntimeArray args, int ctx) {
        RuntimeList enabledFeatures = new RuntimeList();
        for (String feature : getFeatureList()) {
            if (featureManager.isFeatureEnabled(feature)) {
                enabledFeatures.elements.add(new RuntimeScalar(feature));
            }
        }
        return new RuntimeList(enabledFeatures);
    }

    /**
     * Returns the feature bundle selected at a given level in the call stack.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing the feature bundle name.
     */
    public static RuntimeList feature_bundle(RuntimeArray args, int ctx) {
        // This is a placeholder implementation. Adjust according to your stack management.
        return new RuntimeScalar(":default").getList();
    }
}
