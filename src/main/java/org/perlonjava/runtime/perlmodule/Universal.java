package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.mro.InheritanceResolver;
import org.perlonjava.runtime.operators.VersionHelper;
import org.perlonjava.runtime.runtimetypes.*;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.getScalarBoolean;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.*;

/**
 * The Universal class provides methods that are universally available to all objects in a Perl-like environment.
 * It extends PerlModuleBase to leverage module initialization and method registration.
 */
public class Universal extends PerlModuleBase {

    private static String tryDecodeUtf8Octets(String maybeOctets) {
        if (maybeOctets == null || maybeOctets.isEmpty()) {
            return null;
        }
        // Only attempt decoding when the string looks like a byte string (0..255).
        for (int i = 0; i < maybeOctets.length(); i++) {
            if (maybeOctets.charAt(i) > 0xFF) {
                return null;
            }
        }

        byte[] bytes = new byte[maybeOctets.length()];
        for (int i = 0; i < maybeOctets.length(); i++) {
            bytes[i] = (byte) maybeOctets.charAt(i);
        }

        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static String toUtf8OctetString(String unicodeString) {
        if (unicodeString == null || unicodeString.isEmpty()) {
            return null;
        }
        byte[] bytes = unicodeString.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            out.append((char) (b & 0xFF));
        }
        return out.toString();
    }

    /**
     * Constructor for Universal.
     * Initializes the module with the name "UNIVERSAL".
     */
    public Universal() {
        super("UNIVERSAL");
    }

    /**
     * Static initializer to set up the UNIVERSAL module.
     * This method registers universally available methods.
     */
    public static void initialize() {
        Universal universal = new Universal();
        try {
            // Register methods with their respective signatures
            universal.registerMethod("can", "$$");
            universal.registerMethod("isa", "$$");
            universal.registerMethod("DOES", "$$");
            universal.registerMethod("VERSION", "$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing UNIVERSAL method: " + e.getMessage());
        }
    }

    /**
     * Checks if the object can perform a given method.
     * Note: This is a Perl method, it expects `this` to be the first argument.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing the method if it can be performed, otherwise false.
     */
    public static RuntimeList can(RuntimeArray args, int ctx) {
        if (args.size() != 2) {
            throw new IllegalStateException("Bad number of arguments for can() method");
        }
        RuntimeScalar object = args.get(0);
        String methodName = args.get(1).toString();

        // Retrieve Perl class name
        String perlClassName;
        switch (object.type) {
            case REFERENCE:
            case ARRAYREFERENCE:
            case HASHREFERENCE:
                int blessId = ((RuntimeBase) object.value).blessId;
                if (blessId == 0) {
                    return new RuntimeScalar(false).getList();
                }
                perlClassName = NameNormalizer.getBlessStr(blessId);
                break;
            case UNDEF:
                return new RuntimeScalar(false).getList();
            default:
                perlClassName = object.toString();
                if (perlClassName.isEmpty()) {
                    return new RuntimeScalar(false).getList();
                }
                if (perlClassName.endsWith("::")) {
                    perlClassName = perlClassName.substring(0, perlClassName.length() - 2);
                }
        }

        RuntimeScalar method = InheritanceResolver.findMethodInHierarchy(methodName, perlClassName, null, 0);
        if (method != null) {
            return method.getList();
        }

        // Forward declarations (sub foo;) exist in the stash but are not "defined" in the
        // Perl sense, so findMethodInHierarchy skips them (falling through to AUTOLOAD).
        // However, can() should still return the coderef for forward declarations.
        String normalizedName = NameNormalizer.normalizeVariableName(methodName, perlClassName);
        if (GlobalVariable.existsGlobalCodeRef(normalizedName)) {
            return GlobalVariable.getGlobalCodeRef(normalizedName).getList();
        }

        // Fallback: if either the class name or method name was stored as UTF-8 octets
        // (common when source/strings are treated as raw bytes), retry using a decoded form.
        String decodedMethodName = tryDecodeUtf8Octets(methodName);
        String decodedClassName = tryDecodeUtf8Octets(perlClassName);
        if (decodedMethodName != null || decodedClassName != null) {
            String effectiveMethodName = decodedMethodName != null ? decodedMethodName : methodName;
            String effectiveClassName = decodedClassName != null ? decodedClassName : perlClassName;
            method = InheritanceResolver.findMethodInHierarchy(effectiveMethodName, effectiveClassName, null, 0);
            if (method != null) {
                return method.getList();
            }
        }

        // Fallback 2: if identifiers were stored internally as UTF-8 octets (each byte as a char 0..255),
        // try resolving using that representation.
        String methodNameAsOctets = toUtf8OctetString(methodName);
        String classNameAsOctets = toUtf8OctetString(perlClassName);
        if (methodNameAsOctets != null || classNameAsOctets != null) {
            String effectiveMethodName = methodNameAsOctets != null ? methodNameAsOctets : methodName;
            String effectiveClassName = classNameAsOctets != null ? classNameAsOctets : perlClassName;
            method = InheritanceResolver.findMethodInHierarchy(effectiveMethodName, effectiveClassName, null, 0);
            if (method != null) {
                return method.getList();
            }
        }
        return new RuntimeList();
    }

    /**
     * Checks if the object is of a given class or a subclass.
     * Note: This is a Perl method, it expects `this` to be the first argument.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList indicating if the object is of the given class or subclass.
     */
    public static RuntimeList isa(RuntimeArray args, int ctx) {
        if (args.size() != 2) {
            throw new IllegalStateException("Bad number of arguments for isa() method");
        }
        RuntimeScalar object = args.get(0);
        String argString = args.get(1).toString();

        // Retrieve Perl class name
        String perlClassName;
        int type = object.type;
        switch (type) {
            case REFERENCE:
            case ARRAYREFERENCE:
            case HASHREFERENCE:
            case GLOBREFERENCE:
            case FORMAT:
                int blessId = ((RuntimeBase) object.value).blessId;
                if (blessId == 0) {
                    return getScalarBoolean(
                            type == ARRAYREFERENCE && argString.equals("ARRAY")
                                    || type == HASHREFERENCE && argString.equals("HASH")
                                    || type == REFERENCE && argString.equals("SCALAR")
                                    || type == GLOBREFERENCE && argString.equals("GLOB")
                                    || type == FORMAT && argString.equals("FORMAT")
                    ).getList();
                }
                perlClassName = NameNormalizer.getBlessStr(blessId);
                break;
            case UNDEF:
                return new RuntimeScalar(false).getList();
            default:
                perlClassName = object.toString();
                if (perlClassName.isEmpty()) {
                    return new RuntimeScalar(false).getList();
                }
                if (perlClassName.endsWith("::")) {
                    perlClassName = perlClassName.substring(0, perlClassName.length() - 2);
                }
        }

        // Perl also allows *blessed* references to report their underlying ref type via isa().
        // Example: bless({}, "Pkg")->isa("HASH") is true.
        // IMPORTANT: do NOT apply this to unblessed references, because UNIVERSAL::isa($ref, ...)
        // has special truth tables (see uni/universal.t matrix tests).
        if (object.value instanceof RuntimeBase baseValue && baseValue.blessId != 0) {
            if ((argString.equals("HASH") && baseValue instanceof RuntimeHash)
                    || (argString.equals("ARRAY") && baseValue instanceof RuntimeArray)
                    || (argString.equals("SCALAR") && baseValue instanceof RuntimeScalar)
                    || (argString.equals("GLOB") && baseValue instanceof RuntimeGlob)
                    || (argString.equals("FORMAT") && baseValue instanceof RuntimeFormat)) {
                return getScalarBoolean(true).getList();
            }
        }

        // Get the linearized inheritance hierarchy using C3
        List<String> linearizedClasses = InheritanceResolver.linearizeHierarchy(perlClassName);

        return new RuntimeScalar(linearizedClasses.contains(argString)).getList();
    }

    /**
     * Checks if the object does a given role.
     * This method is equivalent to isa() in this context.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList indicating if the object does the given role.
     */
    public static RuntimeList DOES(RuntimeArray args, int ctx) {
        return isa(args, ctx);
    }

    /**
     * Retrieves the version of the package the object is blessed into.
     * If a REQUIRE argument is provided, it compares the package version with REQUIRE.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeScalar representing the version.
     * @throws PerlCompilerException if the version comparison fails.
     */
    public static RuntimeList VERSION(RuntimeArray args, int ctx) {
        if (args.isEmpty() || args.size() > 2) {
            throw new IllegalStateException("Bad number of arguments for VERSION() method");
        }
        RuntimeScalar object = args.get(0);
        RuntimeScalar wantVersion = args.size() == 2 ? args.get(1) : scalarUndef;

        // Retrieve Perl class name
        String perlClassName;
        switch (object.type) {
            case REFERENCE:
            case ARRAYREFERENCE:
            case HASHREFERENCE:
                int blessId = ((RuntimeBase) object.value).blessId;
                if (blessId == 0) {
                    throw new PerlCompilerException("Object is not blessed into a package");
                }
                perlClassName = NameNormalizer.getBlessStr(blessId);
                break;
            case UNDEF:
                throw new PerlCompilerException("Object is undefined");
            default:
                perlClassName = object.toString();
                if (perlClassName.isEmpty()) {
                    throw new PerlCompilerException("Object is not blessed into a package");
                }
                if (perlClassName.endsWith("::")) {
                    perlClassName = perlClassName.substring(0, perlClassName.length() - 2);
                }
        }

        // Retrieve the $VERSION variable from the package
        String versionVariableName = NameNormalizer.normalizeVariableName("VERSION", perlClassName);
        RuntimeScalar hasVersion = GlobalVariable.getGlobalVariable(versionVariableName);
        if (hasVersion.toString().isEmpty()) {
            throw new PerlCompilerException(perlClassName + " does not define $" + perlClassName + "::VERSION--version check failed");
        }

        if (!wantVersion.getDefinedBoolean()) {
            return hasVersion.getList();
        }

        RuntimeScalar packageVersion = VersionHelper.compareVersion(hasVersion, wantVersion, perlClassName);
        return new RuntimeScalar(packageVersion).getList();
    }
}
