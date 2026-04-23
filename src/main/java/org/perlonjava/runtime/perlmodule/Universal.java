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

    /**
     * Constructor for Universal.
     * Initializes the module with the name "UNIVERSAL".
     */
    public Universal() {
        super("UNIVERSAL");
    }

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
     * Static initializer to set up the UNIVERSAL module.
     * This method registers universally available methods.
     */
    public static void initialize() {
        Universal universal = new Universal();
        try {
            // Register UNIVERSAL methods without prototypes. In real Perl,
            // UNIVERSAL::isa / can / DOES / VERSION are plain subs with no
            // prototype; forcing "$$" here rejects valid call patterns like
            // `UNIVERSAL::isa(@_)` (used e.g. by Math::BigRat).
            universal.registerMethod("can", null);
            universal.registerMethod("isa", null);
            universal.registerMethod("DOES", null);
            universal.registerMethod("VERSION", null);
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
            case GLOBREFERENCE:
            case FORMAT:
            case CODE:
            case REGEX:
                int blessId = ((RuntimeBase) object.value).blessId;
                if (blessId == 0) {
                    if (object.type == REGEX) {
                        // qr// objects are implicitly "Regexp" class
                        perlClassName = "Regexp";
                        break;
                    }
                    return new RuntimeScalar(false).getList();
                }
                perlClassName = NameNormalizer.getBlessStr(blessId);
                break;
            case READONLY_SCALAR:
                return can(new RuntimeArray(new RuntimeList((RuntimeScalar) object.value, args.get(1))), ctx);
            case UNDEF:
                if (object.getDefinedBoolean()) {
                    perlClassName = object.toString();
                    if (perlClassName.isEmpty()) {
                        return new RuntimeScalar(false).getList();
                    }
                    break;
                }
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

        // Handle SUPER::method - search parent classes only (skip index 0)
        // This is used by Mojo::DynamicMethods: $caller->can('SUPER::can')
        if (methodName.startsWith("SUPER::")) {
            String actualMethod = methodName.substring(7);
            RuntimeScalar method = InheritanceResolver.findMethodInHierarchy(
                    actualMethod, perlClassName, perlClassName + "::" + methodName, 1);
            if (method != null && !isAutoloadDispatch(method, actualMethod, perlClassName)) {
                return method.getList();
            }
            return new RuntimeList();
        }

        // Handle Package::SUPER::method syntax
        if (methodName.contains("::SUPER::")) {
            int superIdx = methodName.indexOf("::SUPER::");
            String packageName = methodName.substring(0, superIdx);
            String actualMethod = methodName.substring(superIdx + 9);
            RuntimeScalar method = InheritanceResolver.findMethodInHierarchy(
                    actualMethod, packageName, methodName, 1);
            if (method != null && !isAutoloadDispatch(method, actualMethod, packageName)) {
                return method.getList();
            }
            return new RuntimeList();
        }

        // Perl's can() must NOT consider AUTOLOAD - it should only find
        // methods that are actually defined in the hierarchy.
        // See perlobj: "can cannot know whether an object will be able to
        // provide a method through AUTOLOAD"
        RuntimeScalar method = InheritanceResolver.findMethodInHierarchy(methodName, perlClassName, null, 0);
        if (method != null && !isAutoloadDispatch(method, methodName, perlClassName)) {
            return method.getList();
        }

        String normalizedName = NameNormalizer.normalizeVariableName(methodName, perlClassName);
        if (GlobalVariable.existsGlobalCodeRef(normalizedName)) {
            RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(normalizedName);
            if (codeRef.getDefinedBoolean()) {
                return codeRef.getList();
            }
            // Forward declarations (sub foo;) should be visible to can()
            // even though defined(&foo) returns false.
            // Perl 5: can() returns a code ref for forward-declared subs.
            if (codeRef.value instanceof RuntimeCode rc && rc.isDeclared) {
                return codeRef.getList();
            }
        }

        // Fallback: if either the class name or method name was stored as UTF-8 octets
        // (common when source/strings are treated as raw bytes), retry using a decoded form.
        String decodedMethodName = tryDecodeUtf8Octets(methodName);
        String decodedClassName = tryDecodeUtf8Octets(perlClassName);
        if (decodedMethodName != null || decodedClassName != null) {
            String effectiveMethodName = decodedMethodName != null ? decodedMethodName : methodName;
            String effectiveClassName = decodedClassName != null ? decodedClassName : perlClassName;
            method = InheritanceResolver.findMethodInHierarchy(effectiveMethodName, effectiveClassName, null, 0);
            if (method != null && !isAutoloadDispatch(method, effectiveMethodName, effectiveClassName)) {
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
            if (method != null && !isAutoloadDispatch(method, effectiveMethodName, effectiveClassName)) {
                return method.getList();
            }
        }
        return new RuntimeList();
    }

    /**
     * Check if a method resolution result was found via AUTOLOAD dispatch
     * rather than being a directly defined method.
     * <p>
     * The AUTOLOAD coderef has autoloadVariableName set (e.g. "Foo::AUTOLOAD").
     * We detect AUTOLOAD dispatch by checking if the resolved coderef is actually
     * an AUTOLOAD handler AND the method we asked for is not "AUTOLOAD" itself.
     * We also verify the coderef came from the AUTOLOAD hierarchy by checking
     * that the method doesn't actually exist as a direct definition.
     */
    private static boolean isAutoloadDispatch(RuntimeScalar method, String methodName, String className) {
        if (!(method.value instanceof RuntimeCode code)) {
            return false;
        }
        if (code.autoloadVariableName == null) {
            return false;
        }
        // If the method IS "AUTOLOAD", it's a direct lookup, not AUTOLOAD dispatch
        if ("AUTOLOAD".equals(methodName)) {
            return false;
        }
        // Verify by checking if the method actually exists as a real subroutine
        // in the class hierarchy. The autoloadVariableName indicates it was
        // resolved via the AUTOLOAD fallback path.
        String normalizedName = NameNormalizer.normalizeVariableName(methodName, className);
        if (GlobalVariable.existsGlobalCodeRef(normalizedName)) {
            RuntimeScalar directRef = GlobalVariable.getGlobalCodeRef(normalizedName);
            if (directRef.getDefinedBoolean() && directRef != method) {
                return false; // There's a real method with this name
            }
        }
        return true;
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
            case REGEX:
            case CODE:
                int blessId = ((RuntimeBase) object.value).blessId;
                if (blessId == 0) {
                    // Perl 5 recognises both "Regexp" (ref() spelling) and "REGEXP"
                    // (internal SV type name) for isa() checks on unblessed regexes.
                    // Modules like Params::Validate::PP use the uppercase form in
                    // their type-detection tables (%isas hash).
                    return getScalarBoolean(
                            type == ARRAYREFERENCE && argString.equals("ARRAY")
                                    || type == HASHREFERENCE && argString.equals("HASH")
                                    || type == REFERENCE && argString.equals("SCALAR")
                                            && !(object.value instanceof RuntimeScalar rs && rs.type == RuntimeScalarType.GLOB)
                                    || type == REFERENCE && argString.equals("GLOB")
                                            && object.value instanceof RuntimeScalar rs2 && rs2.type == RuntimeScalarType.GLOB
                                    || type == GLOBREFERENCE && argString.equals("GLOB")
                                    || type == FORMAT && argString.equals("FORMAT")
                                    || type == REGEX && (argString.equals("Regexp") || argString.equals("REGEXP"))
                                    || type == CODE && argString.equals("CODE")
                    ).getList();
                }
                perlClassName = NameNormalizer.getBlessStr(blessId);
                break;
            case UNDEF:
                if (object.getDefinedBoolean()) {
                    perlClassName = object.toString();
                    if (perlClassName.isEmpty()) {
                        return new RuntimeScalar(false).getList();
                    }
                    break;
                }
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

        // Normalize the argument: main::Foo -> Foo, ::Foo -> Foo, Foo'Bar -> Foo::Bar
        // This is needed because isa("main::Foo") should match a class blessed as "Foo"
        String normalizedArg = argString;
        // First normalize old-style ' separator to ::
        normalizedArg = NameNormalizer.normalizePackageName(normalizedArg);
        if (normalizedArg.startsWith("main::")) {
            normalizedArg = normalizedArg.substring(6);
        } else if (normalizedArg.startsWith("::")) {
            normalizedArg = normalizedArg.substring(2);
        }
        // Canonicalise through stash aliases (`*Foo:: = *Bar::;`): an argument
        // like "Dummy::True" must still match an object blessed into "JSON::PP::Boolean"
        // if the two package names are aliases.
        normalizedArg = GlobalVariable.resolveStashAlias(normalizedArg);

        return new RuntimeScalar(linearizedClasses.contains(normalizedArg)).getList();
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
            case GLOBREFERENCE:
            case FORMAT:
            case CODE:
            case REGEX:
                int blessId = ((RuntimeBase) object.value).blessId;
                if (blessId == 0) {
                    throw new PerlCompilerException("Object is not blessed into a package");
                }
                perlClassName = NameNormalizer.getBlessStr(blessId);
                break;
            case READONLY_SCALAR:
                return VERSION(new RuntimeArray(new RuntimeList((RuntimeScalar) object.value, wantVersion)), ctx);
            case UNDEF:
                if (object.getDefinedBoolean()) {
                    perlClassName = object.toString();
                    if (perlClassName.isEmpty()) {
                        throw new PerlCompilerException("Object is not blessed into a package");
                    }
                    break;
                }
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

        // If no version argument was provided, just return the current $VERSION (may be undef)
        // Perl 5: Module->VERSION with no args returns $VERSION or undef, never throws
        if (!wantVersion.getDefinedBoolean()) {
            return hasVersion.getList();
        }

        // A version argument was provided - check requirement
        if (hasVersion.toString().isEmpty()) {
            throw new PerlCompilerException(perlClassName + " does not define $" + perlClassName + "::VERSION--version check failed");
        }

        RuntimeScalar packageVersion = VersionHelper.compareVersion(hasVersion, wantVersion, perlClassName);
        return new RuntimeScalar(packageVersion).getList();
    }
}
