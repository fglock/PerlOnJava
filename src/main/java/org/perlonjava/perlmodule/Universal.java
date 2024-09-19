package org.perlonjava.perlmodule;

import org.perlonjava.runtime.*;

import java.lang.reflect.Method;
import java.util.List;

import static org.perlonjava.runtime.GlobalContext.getGlobalCodeRef;
import static org.perlonjava.runtime.GlobalContext.getGlobalHash;

public class Universal {

    public static void initialize() {
        // Initialize UNIVERSAL class

        // Set %INC
        getGlobalHash("main::INC").put("UNIVERSAL.pm", new RuntimeScalar("UNIVERSAL.pm"));

        try {
            // UNIVERSAL methods are defined in RuntimeScalar class
            Class<?> clazz = Universal.class;
            RuntimeScalar instance = new RuntimeScalar();

            Method mm = clazz.getMethod("can", RuntimeArray.class, int.class);
            getGlobalCodeRef("UNIVERSAL::can").set(new RuntimeScalar(
                    new RuntimeCode(mm, instance, null)));

            mm = clazz.getMethod("isa", RuntimeArray.class, int.class);
            getGlobalCodeRef("UNIVERSAL::isa").set(new RuntimeScalar(
                    new RuntimeCode(mm, instance, null)));
            getGlobalCodeRef("UNIVERSAL::DOES").set(new RuntimeScalar(
                    new RuntimeCode(mm, instance, null)));
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing UNIVERSAL method: " + e.getMessage());
        }
    }

    // Checks if the object can perform a given method
    // Note this is a Perl method, it expects `this` to be the first argument
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
                int blessId = ((RuntimeBaseEntity) object.value).blessId;
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

        // Check the method cache
        String normalizedMethodName = NameNormalizer.normalizeVariableName(methodName, perlClassName);
        RuntimeScalar cachedMethod = InheritanceResolver.getCachedMethod(normalizedMethodName);
        if (cachedMethod != null) {
            return cachedMethod.getList();
        }

        // Get the linearized inheritance hierarchy using C3
        for (String className : InheritanceResolver.linearizeC3(perlClassName)) {
            String normalizedClassMethodName = NameNormalizer.normalizeVariableName(methodName, className);
            if (GlobalContext.existsGlobalCodeRef(normalizedClassMethodName)) {
                // If the method is found, return it
                return getGlobalCodeRef(normalizedClassMethodName).getList();
            }
        }
        return new RuntimeScalar(false).getList();
    }

    // Checks if the object is of a given class or a subclass
    // Note this is a Perl method, it expects `this` to be the first argument
    public static RuntimeList isa(RuntimeArray args, int ctx) {
        if (args.size() != 2) {
            throw new IllegalStateException("Bad number of arguments for isa() method");
        }
        RuntimeScalar object = args.get(0);
        String argString = args.get(1).toString();

        // Retrieve Perl class name
        String perlClassName;
        switch (object.type) {
            case REFERENCE:
            case ARRAYREFERENCE:
            case HASHREFERENCE:
                int blessId = ((RuntimeBaseEntity) object.value).blessId;
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

        // Get the linearized inheritance hierarchy using C3
        List<String> linearizedClasses = InheritanceResolver.linearizeC3(perlClassName);

        return new RuntimeScalar(linearizedClasses.contains(argString)).getList();
    }
}
