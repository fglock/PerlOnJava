package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.mro.InheritanceResolver;

import java.util.List;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;

public class NextMethod {
    private static final boolean DEBUG_NEXT_METHOD = Boolean.getBoolean("debug.next.method");

    private record CallerMethod(String callerPackage, String methodName) {}

    /**
     * Package for SUPER / next::method resolution: named-glob installs ({@code *Pkg::m = sub {...}})
     * record {@link RuntimeCode#stashInstallPackage}; anonymous bodies may still have the
     * enclosing compile-time {@link RuntimeCode#packageName} (e.g. Class::Std wrappers).
     */
    private static String methodResolutionPackage(RuntimeCode code) {
        if (code == null) {
            return null;
        }
        if (code.stashInstallPackage != null && !code.stashInstallPackage.isEmpty()) {
            return code.stashInstallPackage;
        }
        return code.packageName;
    }

    private static CallerMethod resolveCallerMethodFromStack() {
        for (int level = 0; level <= 5; level++) {
            try {
                RuntimeList levelArgs = new RuntimeScalar(level).getList();
                RuntimeList callerInfo = RuntimeCode.caller(levelArgs, RuntimeContextType.LIST);

                if (callerInfo.elements.size() >= 4) {
                    String pkg = callerInfo.elements.get(0).toString();
                    String filename = callerInfo.elements.get(1).toString();
                    String subroutineName = callerInfo.elements.get(3).toString();

                    if (!filename.contains(".java") && !pkg.isEmpty()
                            && !subroutineName.isEmpty() && !subroutineName.equals("(eval)")
                            && !subroutineName.endsWith("::__ANON__")
                            && !subroutineName.startsWith("next::")
                            && !subroutineName.startsWith("maybe::next::")) {
                        String methodName;
                        String callerPackage;
                        int lastDoubleColon = subroutineName.lastIndexOf("::");
                        if (lastDoubleColon >= 0) {
                            methodName = subroutineName.substring(lastDoubleColon + 2);
                            callerPackage = subroutineName.substring(0, lastDoubleColon);
                        } else {
                            methodName = subroutineName;
                            callerPackage = pkg;
                        }

                        if (!methodName.isEmpty() && !methodName.equals("(eval)")) {
                            return new CallerMethod(callerPackage, methodName);
                        }
                    }
                }
            } catch (Exception e) {
                // Continue trying other levels.
            }
        }
        return null;
    }

    /**
     * next::method with proper context from currentSub
     */
    public static RuntimeList nextMethodWithContext(RuntimeArray args, RuntimeScalar currentSub, int ctx) {
        if (DEBUG_NEXT_METHOD) {
            System.out.println("DEBUG: === nextMethodWithContext called ===");
            System.out.println("DEBUG: args.size() = " + args.size());
            System.out.println("DEBUG: currentSub = " + currentSub);
        }

        if (args.size() == 0) {
            throw new PerlCompilerException("Can't call next::method on an empty argument list");
        }

        if (currentSub == null || currentSub.type != RuntimeScalarType.CODE) {
            throw new PerlCompilerException("next::method called outside of method context");
        }

        RuntimeCode code = (RuntimeCode) currentSub.value;
        String callerPackage = methodResolutionPackage(code);
        String methodName =
                code.stashInstallSub != null && !code.stashInstallSub.isEmpty()
                        ? code.stashInstallSub
                        : code.subName;

        if (code.installedViaAnonGlobAssign && !code.explicitlyRenamed) {
            throw new PerlCompilerException(
                    "next::method/next::can/maybe::next::method cannot find enclosing method");
        }

        boolean realSubName =
                methodName != null
                        && !methodName.isEmpty()
                        && !"__ANON__".equals(methodName);
        if (!code.explicitlyRenamed && (callerPackage == null || !realSubName)) {
            return nextMethod(args, ctx);
        }

        if (DEBUG_NEXT_METHOD) {
            System.out.println("DEBUG: callerPackage = " + callerPackage);
            System.out.println("DEBUG: methodName = " + methodName);
        }

        return findAndCallNextMethod(args, callerPackage, methodName, ctx);
    }

    /**
     * next::can with proper context from currentSub
     */
    public static RuntimeList nextCanWithContext(RuntimeArray args, RuntimeScalar currentSub, int ctx) {
        if (DEBUG_NEXT_METHOD) {
            System.out.println("DEBUG: === nextCanWithContext called ===");
        }

        if (args.size() == 0) {
            throw new PerlCompilerException("Can't call next::can on an empty argument list");
        }

        if (currentSub == null || currentSub.type != RuntimeScalarType.CODE) {
            throw new PerlCompilerException("next::can called outside of method context");
        }

        RuntimeCode code = (RuntimeCode) currentSub.value;
        String callerPackage = methodResolutionPackage(code);
        String methodName =
                code.stashInstallSub != null && !code.stashInstallSub.isEmpty()
                        ? code.stashInstallSub
                        : code.subName;

        if (code.installedViaAnonGlobAssign && !code.explicitlyRenamed) {
            return scalarUndef.getList();
        }

        boolean realSubName =
                methodName != null
                        && !methodName.isEmpty()
                        && !"__ANON__".equals(methodName);
        if (!code.explicitlyRenamed && (callerPackage == null || !realSubName)) {
            return nextCan(args, ctx);
        }

        try {
            RuntimeScalar nextMethod = findNextMethod(args, callerPackage, methodName);
            return nextMethod != null ? nextMethod.getList() : scalarUndef.getList();
        } catch (PerlCompilerException e) {
            if (DEBUG_NEXT_METHOD) {
                System.out.println("DEBUG: next::can caught exception: " + e.getMessage());
            }
            return scalarUndef.getList();
        }
    }

    /**
     * maybe::next::method with proper context from currentSub
     */
    public static RuntimeList maybeNextMethodWithContext(RuntimeArray args, RuntimeScalar currentSub, int ctx) {
        if (DEBUG_NEXT_METHOD) {
            System.out.println("DEBUG: === maybeNextMethodWithContext called ===");
        }

        try {
            return nextMethodWithContext(args, currentSub, ctx);
        } catch (PerlCompilerException e) {
            if (DEBUG_NEXT_METHOD) {
                System.out.println("DEBUG: maybe::next::method caught exception: " + e.getMessage());
            }
            return scalarUndef.getList();
        }
    }

    /**
     * Core logic to find and call the next method in the hierarchy
     */
    private static RuntimeList findAndCallNextMethod(RuntimeArray args, String callerPackage, String methodName, int ctx) {
        if (DEBUG_NEXT_METHOD) {
            System.out.println("DEBUG: === findAndCallNextMethod ===");
            System.out.println("DEBUG: callerPackage = " + callerPackage);
            System.out.println("DEBUG: methodName = " + methodName);
        }

        RuntimeScalar firstArg = args.get(0);
        String searchClass = getSearchClass(firstArg);

        if (DEBUG_NEXT_METHOD) {
            System.out.println("DEBUG: searchClass = " + searchClass);
        }

        RuntimeScalar nextMethod = findNextMethod(args, callerPackage, methodName, searchClass);

        if (nextMethod == null || !nextMethod.getDefinedBoolean()) {
            throw new PerlCompilerException("No next::method '" + methodName + "' found for " + searchClass);
        }

        if (DEBUG_NEXT_METHOD) {
            System.out.println("DEBUG: Calling next method");
        }

        // Call the next method directly
        return RuntimeCode.apply(nextMethod, args, ctx);
    }

    /**
     * Find the next method in the hierarchy
     */
    private static RuntimeScalar findNextMethod(RuntimeArray args, String callerPackage, String methodName) {
        RuntimeScalar firstArg = args.get(0);
        String searchClass = getSearchClass(firstArg);
        return findNextMethod(args, callerPackage, methodName, searchClass);
    }

    /**
     * Find the next method in the hierarchy with explicit search class
     */
    private static RuntimeScalar findNextMethod(RuntimeArray args, String callerPackage, String methodName, String searchClass) {
        // Get the linearized inheritance hierarchy always using C3.
        // In Perl 5, next::method always uses C3 regardless of the class's MRO setting.
        List<String> linearized = InheritanceResolver.linearizeC3Always(searchClass);

        if (DEBUG_NEXT_METHOD) {
            System.out.println("DEBUG: linearization = " + linearized);
        }

        // Find the calling class in the linearization
        int callerIndex = linearized.indexOf(callerPackage);
        if (callerIndex == -1) {
            if (DEBUG_NEXT_METHOD) {
                System.out.println("DEBUG: Caller package '" + callerPackage + "' not found in linearization");
                System.out.println("DEBUG: Available classes in linearization: " + linearized);
            }
            throw new PerlCompilerException("Can't find calling class '" + callerPackage + "' in method resolution order for " + searchClass);
        }

        if (DEBUG_NEXT_METHOD) {
            System.out.println("DEBUG: Found callerPackage at index " + callerIndex + ", searching from index " + (callerIndex + 1));
        }

        // Search for the next method starting after the calling class
        for (int i = callerIndex + 1; i < linearized.size(); i++) {
            String className = linearized.get(i);
            String normalizedMethodName = NameNormalizer.normalizeVariableName(methodName, className);

            if (DEBUG_NEXT_METHOD) {
                System.out.println("DEBUG: Checking for method '" + methodName + "' in class '" + className +
                        "' (normalized: '" + normalizedMethodName + "')");
            }

            if (GlobalVariable.existsGlobalCodeRef(normalizedMethodName)) {
                RuntimeScalar nextMethod = GlobalVariable.getGlobalCodeRef(normalizedMethodName);
                if (nextMethod.getDefinedBoolean()) {
                    if (DEBUG_NEXT_METHOD) {
                        System.out.println("DEBUG: Found next method in " + className);
                    }
                    return nextMethod;
                }
            } else {
                if (DEBUG_NEXT_METHOD) {
                    System.out.println("DEBUG: Method not found in " + className);
                }
            }
        }

        return null; // No next method found
    }

    /**
     * Determine the search class from the first argument
     */
    private static String getSearchClass(RuntimeScalar firstArg) {
        switch (firstArg.type) {
            case RuntimeScalarType.REFERENCE:
            case RuntimeScalarType.ARRAYREFERENCE:
            case RuntimeScalarType.HASHREFERENCE:
                int blessId = ((RuntimeBase) firstArg.value).blessId;
                if (blessId == 0) {
                    throw new PerlCompilerException("Can't call next::method on unblessed reference");
                }
                return NameNormalizer.getBlessStr(blessId);
            case RuntimeScalarType.READONLY_SCALAR:
                return getSearchClass((RuntimeScalar) firstArg.value);
            case RuntimeScalarType.UNDEF:
                throw new PerlCompilerException("Can't call next::method on an undefined value");
            default:
                String searchClass = firstArg.toString();
                if (searchClass.isEmpty()) {
                    throw new PerlCompilerException("Can't call next::method on empty string");
                }
                return searchClass;
        }
    }

    // Keep original methods as fallbacks for direct calls
    public static RuntimeList nextMethod(RuntimeArray args, int ctx) {
        if (DEBUG_NEXT_METHOD) {
            System.out.println("DEBUG: === nextMethod (fallback) called ===");
            System.out.println("DEBUG: This should not be used in normal operation");
        }

        if (args.size() == 0) {
            throw new PerlCompilerException("Can't call next::method on an empty argument list");
        }

        CallerMethod caller = resolveCallerMethodFromStack();
        if (caller == null) {
            throw new PerlCompilerException("Can't resolve method name for next::method");
        }

        return findAndCallNextMethod(args, caller.callerPackage(), caller.methodName(), ctx);
    }

    public static RuntimeList nextCan(RuntimeArray args, int ctx) {
        try {
            if (args.size() == 0) {
                return scalarUndef.getList();
            }
            CallerMethod caller = resolveCallerMethodFromStack();
            if (caller == null) {
                return scalarUndef.getList();
            }
            RuntimeScalar nextMethod = findNextMethod(args, caller.callerPackage(), caller.methodName());
            return nextMethod != null ? nextMethod.getList() : scalarUndef.getList();
        } catch (Exception e) {
            if (DEBUG_NEXT_METHOD) {
                System.out.println("DEBUG: next::can caught exception: " + e.getMessage());
            }
            return scalarUndef.getList();
        }
    }

    public static RuntimeList maybeNextMethod(RuntimeArray args, int ctx) {
        try {
            return nextMethod(args, ctx);
        } catch (PerlCompilerException e) {
            return scalarUndef.getList();
        }
    }

    static RuntimeScalar superMethod(RuntimeScalar currentSub, String methodName, String fallbackPackage) {
        RuntimeScalar method;
        String packageName = fallbackPackage;
        if (currentSub != null && currentSub.value instanceof RuntimeCode code) {
            String mrp = methodResolutionPackage(code);
            if (mrp != null && !mrp.isEmpty()) {
                packageName = mrp;
            }
        }
        method = InheritanceResolver.findMethodInHierarchy(
                methodName.substring(7),    // method name without SUPER:: prefix
                packageName,
                packageName + "::" + methodName,  // cache key includes the SUPER:: prefix
                1   // start looking in the parent package
        );
        return method;
    }
}
