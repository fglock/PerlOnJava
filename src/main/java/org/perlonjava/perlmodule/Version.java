package org.perlonjava.perlmodule;

import org.perlonjava.runtime.*;
import org.perlonjava.operators.VersionHelper;
import org.perlonjava.operators.ReferenceOperators;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.perlonjava.runtime.RuntimeScalarCache.*;
import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;

/**
 * The {@code Version} class provides methods for handling version objects
 * within a Perl-like runtime environment. It extends {@link PerlModuleBase} and
 * offers functionality to create, parse, and compare version numbers.
 */
public class Version extends PerlModuleBase {

    /**
     * Constructs a new {@code Version} instance and initializes the module with the name "version".
     */
    public Version() {
        super("version", false);
    }

    /**
     * Initializes the Version module by registering methods.
     */
    public static void initialize() {
        Version version = new Version();
        try {
            version.registerMethod("declare", "$");
            version.registerMethod("qv", "$");
            version.registerMethod("_VERSION", "VERSION", "$$");
            version.registerMethod("vcmp", "VCMP", "$$");
            // version.registerMethod("new", "$");
            version.registerMethod("numify", "$");
            version.registerMethod("normal", "$");
            version.registerMethod("to_decimal", "$");
            version.registerMethod("to_dotted_decimal", "$");
            version.registerMethod("tuple", "$");
            version.registerMethod("from_tuple", "@");
            version.registerMethod("stringify", "$");
            version.registerMethod("parse", "$");

//            // Register overloaded operators
//            version.registerMethod("(\"\"", "stringify", "$");
//            version.registerMethod("(<=>", "VCMP", "$$");
//            version.registerMethod("(cmp", "VCMP", "$$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Version method: " + e.getMessage());
        }
    }

//    /**
//     * Creates a new version object from a string.
//     */
//    public static RuntimeList new_(RuntimeArray args, int ctx) {
//        if (args.size() < 2) {
//            throw new IllegalStateException("version->new() requires an argument");
//        }
//        RuntimeScalar versionStr = args.get(1);
//        return parse(args, ctx);
//    }

    /**
     * Parses a version string into a version object.
     */
    public static RuntimeList parse(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            throw new IllegalStateException("version->parse() requires an argument");
        }

        RuntimeScalar versionStr = args.get(1);
        String version = versionStr.toString();

        // Create a blessed version object
        RuntimeHash versionObj = new RuntimeHash();

        // Parse the version string
        if (version.startsWith("v")) {
            // v-string format
            versionObj.put("original", new RuntimeScalar(version));
            versionObj.put("alpha", scalarFalse);
            versionObj.put("qv", scalarTrue);

            // Parse components
            String normalized = VersionHelper.normalizeVersion(versionStr);
            versionObj.put("version", new RuntimeScalar(normalized));
        } else {
            // Decimal format
            boolean isAlpha = version.contains("_");
            String cleanVersion = version.replace("_", "");

            versionObj.put("original", new RuntimeScalar(version));
            versionObj.put("alpha", getScalarBoolean(isAlpha));
            versionObj.put("qv", scalarFalse);

            // Normalize the version
            String normalized = VersionHelper.normalizeVersion(new RuntimeScalar(cleanVersion));
            versionObj.put("version", new RuntimeScalar(normalized));
        }

        // Bless the object
        RuntimeScalar blessed = versionObj.createReference();
        ReferenceOperators.bless(blessed, new RuntimeScalar("version"));

        return blessed.getList();
    }

    /**
     * Creates a dotted-decimal version object.
     * This is a method that expects to be called as version->declare()
     */
    public static RuntimeList declare(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            throw new IllegalStateException("version->declare() requires an argument");
        }

        RuntimeScalar versionStr = args.get(1);
        String version = versionStr.toString();

        // Ensure it's in dotted-decimal format
        if (!version.startsWith("v") && !version.contains(".")) {
            version = "v" + version + ".0.0";
        } else if (!version.startsWith("v")) {
            version = "v" + version;
        }

        // Create a new RuntimeArray with the modified version
        RuntimeArray newArgs = new RuntimeArray();
        newArgs.push(args.get(0));  // class name
        newArgs.push(new RuntimeScalar(version));

        return parse(newArgs, ctx);
    }

    /**
     * qv() - creates a dotted-decimal version object.
     * Always receives class name as first argument due to how it's exported.
     */
    public static RuntimeList qv(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new IllegalStateException("qv() requires an argument");
        }

        RuntimeScalar versionStr = RuntimeArray.pop(args);
        String version = versionStr.toString();

        // Ensure it's in dotted-decimal format
        if (!version.startsWith("v") && !version.contains(".")) {
            version = "v" + version + ".0.0";
        } else if (!version.startsWith("v")) {
            version = "v" + version;
        }

        // Create version object via parse
        RuntimeArray parseArgs = new RuntimeArray();
        parseArgs.push(args.get(0));  // class name
        parseArgs.push(new RuntimeScalar(version));

        return parse(parseArgs, ctx);
    }

    /**
     * Returns the numified representation of the version.
     */
    public static RuntimeList numify(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new IllegalStateException("numify requires an argument");
        }

        RuntimeScalar self = args.get(0);
        RuntimeHash versionObj = self.hashDeref();

        String version = versionObj.get("version").toString();
        String[] parts = version.split("\\.");

        if (parts.length == 0) {
            return new RuntimeScalar(0.0).getList();
        }

        // Convert to decimal: major.minorpatch
        double major = Double.parseDouble(parts[0]);
        double minor = parts.length > 1 ? Double.parseDouble(parts[1]) : 0;
        double patch = parts.length > 2 ? Double.parseDouble(parts[2]) : 0;

        double numified = major + (minor / 1000.0) + (patch / 1000000.0);

        return new RuntimeScalar(numified).getList();
    }

    /**
     * Returns the normalized dotted-decimal form with leading v.
     */
    public static RuntimeList normal(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new IllegalStateException("normal requires an argument");
        }

        RuntimeScalar self = args.get(0);
        RuntimeHash versionObj = self.hashDeref();

        String version = versionObj.get("version").toString();
        String[] parts = version.split("\\.");

        // Ensure at least 3 components
        StringBuilder normal = new StringBuilder("v");
        for (int i = 0; i < 3; i++) {
            if (i > 0) normal.append(".");
            if (i < parts.length) {
                normal.append(parts[i]);
            } else {
                normal.append("0");
            }
        }

        return new RuntimeScalar(normal.toString()).getList();
    }

    /**
     * Converts to decimal version object.
     */
    public static RuntimeList to_decimal(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new IllegalStateException("to_decimal requires an argument");
        }

        // Get numified version
        RuntimeList numified = numify(args, ctx);

        // Parse it as a new version object
        RuntimeArray parseArgs = new RuntimeArray();
        parseArgs.push(new RuntimeScalar("version"));
        parseArgs.push(numified.elements.get(0));

        return parse(parseArgs, ctx);
    }

    /**
     * Converts to dotted decimal version object.
     */
    public static RuntimeList to_dotted_decimal(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new IllegalStateException("to_dotted_decimal requires an argument");
        }

        // Get normalized version
        RuntimeList normalized = normal(args, ctx);

        // Parse it as a new version object
        RuntimeArray parseArgs = new RuntimeArray();
        parseArgs.push(new RuntimeScalar("version"));
        parseArgs.push(normalized.elements.get(0));

        return parse(parseArgs, ctx);
    }

    /**
     * Returns version components as a list.
     */
    public static RuntimeList tuple(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new IllegalStateException("tuple requires an argument");
        }

        RuntimeScalar self = args.get(0);
        RuntimeHash versionObj = self.hashDeref();

        String version = versionObj.get("version").toString();
        String[] parts = version.split("\\.");

        RuntimeArray result = new RuntimeArray();
        for (String part : parts) {
            result.push(new RuntimeScalar(Integer.parseInt(part)));
        }

        return result.getList();
    }

    /**
     * Creates a version object from a list of components.
     */
    public static RuntimeList from_tuple(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            throw new IllegalStateException("from_tuple requires at least one component");
        }

        StringBuilder version = new StringBuilder("v");
        for (int i = 1; i < args.size(); i++) {
            if (i > 1) version.append(".");
            version.append(args.get(i).toString());
        }

        RuntimeArray parseArgs = new RuntimeArray();
        parseArgs.push(args.get(0)); // class name
        parseArgs.push(new RuntimeScalar(version.toString()));

        return parse(parseArgs, ctx);
    }

    /**
     * Returns string representation of the version.
     */
    public static RuntimeList stringify(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new IllegalStateException("stringify requires an argument");
        }

        RuntimeScalar self = args.get(0);
        RuntimeHash versionObj = self.hashDeref();

        // Return the original representation
        return versionObj.get("original").getList();
    }

    /**
     * Compares two version objects.
     */
    public static RuntimeList VCMP(RuntimeArray args, int ctx) {
        if (args.size() < 2) {
            throw new IllegalStateException("vcmp requires two arguments");
        }

        RuntimeScalar v1 = args.get(0);
        RuntimeScalar v2 = args.get(1);

        // Handle non-version objects
        if (!v1.isBlessed() || !NameNormalizer.getBlessStr(v1.blessId).equals("version")) {
            RuntimeArray parseArgs = new RuntimeArray();
            parseArgs.push(new RuntimeScalar("version"));
            parseArgs.push(v1);
            v1 = parse(parseArgs, RuntimeContextType.SCALAR).scalar();
        }

        if (!v2.isBlessed() || !NameNormalizer.getBlessStr(v2.blessId).equals("version")) {
            RuntimeArray parseArgs = new RuntimeArray();
            parseArgs.push(new RuntimeScalar("version"));
            parseArgs.push(v2);
            v2 = parse(parseArgs, RuntimeContextType.SCALAR).scalar();
        }

        // Get normalized versions
        RuntimeHash obj1 = v1.hashDeref();
        RuntimeHash obj2 = v2.hashDeref();

        String ver1 = obj1.get("version").toString();
        String ver2 = obj2.get("version").toString();

        // Compare versions
        String[] v1Parts = ver1.split("\\.");
        String[] v2Parts = ver2.split("\\.");
        int length = Math.max(v1Parts.length, v2Parts.length);
        int cmp = 0;
        for (int i = 0; i < length; i++) {
            int v1Part = i < v1Parts.length ? Integer.parseInt(v1Parts[i]) : 0;
            int v2Part = i < v2Parts.length ? Integer.parseInt(v2Parts[i]) : 0;
            if (v1Part != v2Part) {
                cmp = v1Part - v2Part;
                break;
            }
        }

        return new RuntimeScalar(cmp).getList();
    }

    /**
     * Implementation of UNIVERSAL::VERSION.
     */
    public static RuntimeList VERSION(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new IllegalStateException("VERSION requires at least one argument");
        }

        RuntimeScalar pkg = args.get(0);
        String packageName = pkg.toString();

        // Get the package's $VERSION
        RuntimeScalar hasVersion = getGlobalVariable(packageName + "::VERSION");

        if (args.size() == 1) {
            // Just return the version
            return hasVersion.getList();
        }

        // Check version requirement
        RuntimeScalar wantVersion = args.get(1);
        try {
            RuntimeScalar result = VersionHelper.compareVersion(hasVersion, wantVersion, packageName);
            return result.getList();
        } catch (PerlCompilerException e) {
            throw e;
        }
    }
}
