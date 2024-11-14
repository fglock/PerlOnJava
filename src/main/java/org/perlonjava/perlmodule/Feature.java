package org.perlonjava.perlmodule;

import org.perlonjava.runtime.FeatureFlags;
import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

/**
 * The FeatureFlags class provides functionalities similar to the Perl feature module.
 */
public class Feature extends PerlModuleBase {

    public static final FeatureFlags featureManager = new FeatureFlags();

    /**
     * Constructor for FeatureFlags.
     * Initializes the module with the name "feature".
     */
    public Feature() {
        super("feature");
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
    }

    /**
     * Enables a feature bundle.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList.
     */
    public static RuntimeList useFeature(RuntimeArray args, int ctx) {
        for (int i = 1; i < args.size(); i++) {
            String bundle = args.get(i).toString();
            featureManager.enableFeatureBundle(bundle);
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
        for (int i = 1; i < args.size(); i++) {
            String bundle = args.get(i).toString();
            if (":all".equals(bundle)) {
                for (String feature : FeatureFlags.getFeatureList()) {
                    featureManager.disableFeatureBundle(feature);
                }
            } else {
                featureManager.disableFeatureBundle(bundle);
            }
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
        for (String feature : FeatureFlags.getFeatureList()) {
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
