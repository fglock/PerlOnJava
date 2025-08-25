package org.perlonjava.runtime;

import org.perlonjava.mro.C3;
import org.perlonjava.mro.InheritanceResolver;

import java.util.List;

import static org.perlonjava.runtime.RuntimeScalarCache.scalarOne;

/**
 * Implements the next::method, next::can, and maybe::next::method functionality.
 * These always use C3 MRO regardless of the current MRO setting.
 */
public class NextMethod {

    private static final boolean DEBUG_NEXT_METHOD = Boolean.getBoolean("debug.next.method");

    public static RuntimeList nextMethod(RuntimeArray args, int ctx) {
        if (DEBUG_NEXT_METHOD) {
            System.out.println("DEBUG: nextMethod called with " + args.size() + " arguments");
        }

        // Get the object/class from the first argument
        if (args.size() == 0) {
            throw new PerlCompilerException("Can't call next::method on an empty argument list");
        }

        RuntimeScalar firstArg = args.get(0);
        String searchClass;

        // Determine the class to search from
        switch (firstArg.type) {
            case RuntimeScalarType.REFERENCE:
            case RuntimeScalarType.ARRAYREFERENCE:
            case RuntimeScalarType.HASHREFERENCE:
                int blessId = ((RuntimeBase) firstArg.value).blessId;
                if (blessId == 0) {
                    throw new PerlCompilerException("Can't call next::method on unblessed reference");
                }
                searchClass = NameNormalizer.getBlessStr(blessId);
                break;
            default:
                searchClass = firstArg.toString();
                if (searchClass.isEmpty()) {
                    throw new PerlCompilerException("Can't call next::method on empty string");
                }
        }

        // FIXED: Better approach to get calling method information
        // Get caller information from multiple levels to find the actual calling method
        String callerPackage = null;
        String methodName = null;

        // Try caller levels 0, 1, 2 to find the right context
        for (int level = 0; level <= 3; level++) {
            RuntimeList levelArgs = new RuntimeScalar(level).getList();
            RuntimeList callerInfo = RuntimeCode.caller(levelArgs, RuntimeContextType.LIST);

            if (DEBUG_NEXT_METHOD) {
                System.out.println("DEBUG: caller(" + level + ") = " +
                        (callerInfo.elements.size() > 0 ? callerInfo.elements.get(0).toString() : "no info"));
            }

            if (callerInfo.elements.size() >= 4) {
                String pkg = callerInfo.elements.get(0).toString();
                String filename = callerInfo.elements.get(1).toString();
                String subroutineName = callerInfo.elements.get(3).toString();

                if (DEBUG_NEXT_METHOD) {
                    System.out.println("DEBUG: Level " + level + " - pkg=" + pkg +
                            ", filename=" + filename + ", sub=" + subroutineName);
                }

                // Look for a Perl method call (not from Mro.java)
                if (!filename.equals("Mro.java") && !pkg.isEmpty() && !subroutineName.isEmpty()) {
                    callerPackage = pkg;

                    // Extract method name from subroutine name
                    if (subroutineName.contains("::")) {
                        int lastDoubleColon = subroutineName.lastIndexOf("::");
                        methodName = subroutineName.substring(lastDoubleColon + 2);
                        callerPackage = subroutineName.substring(0, lastDoubleColon);
                    } else {
                        methodName = subroutineName;
                    }

                    if (DEBUG_NEXT_METHOD) {
                        System.out.println("DEBUG: Found caller - package=" + callerPackage +
                                ", method=" + methodName);
                    }
                    break;
                }
            }
        }

        if (callerPackage == null || methodName == null || methodName.isEmpty()) {
            if (DEBUG_NEXT_METHOD) {
                System.out.println("DEBUG: Could not resolve caller information");
            }
            throw new PerlCompilerException("Can't resolve method name for next::method");
        }

        if (DEBUG_NEXT_METHOD) {
            System.out.println("DEBUG: Resolved - callerPackage=" + callerPackage +
                    ", methodName=" + methodName + ", searchClass=" + searchClass);
        }

        // Get C3 linearization
        List<String> linearized = C3.linearizeC3(searchClass);
        if (DEBUG_NEXT_METHOD) {
            System.out.println("DEBUG: C3 linearization = " + linearized);
        }

        // Find the calling class in the linearization
        int callerIndex = linearized.indexOf(callerPackage);
        if (callerIndex == -1) {
            if (DEBUG_NEXT_METHOD) {
                System.out.println("DEBUG: Caller package '" + callerPackage + "' not found in linearization");
            }
            throw new PerlCompilerException("Can't find calling class '" + callerPackage + "' in method resolution order for " + searchClass);
        }

        if (DEBUG_NEXT_METHOD) {
            System.out.println("DEBUG: callerIndex = " + callerIndex + ", searching from index " + (callerIndex + 1));
        }

        // FIXED: Search for the next method starting after the calling class
        // Make sure we don't find the same method again
        RuntimeScalar nextMethod = null;
        for (int i = callerIndex + 1; i < linearized.size(); i++) {
            String className = linearized.get(i);
            String fullMethodName = className + "::" + methodName;

            if (DEBUG_NEXT_METHOD) {
                System.out.println("DEBUG: Looking for method " + fullMethodName);
            }

            // Check if method exists in this class
            String normalizedMethodName = NameNormalizer.normalizeVariableName(methodName, className);
            if (GlobalVariable.existsGlobalCodeRef(normalizedMethodName)) {
                nextMethod = GlobalVariable.getGlobalCodeRef(normalizedMethodName);
                if (DEBUG_NEXT_METHOD) {
                    System.out.println("DEBUG: Found next method in " + className);
                }
                break;
            }
        }

        if (nextMethod == null || !nextMethod.getDefinedBoolean()) {
            if (DEBUG_NEXT_METHOD) {
                System.out.println("DEBUG: No next method found");
            }
            throw new PerlCompilerException("No next::method '" + methodName + "' found for " + searchClass);
        }

        // Call the next method
        return RuntimeCode.call(firstArg, nextMethod, null, args, ctx);
    }

    // Keep the other methods the same...
    public static RuntimeList nextCan(RuntimeArray args, int ctx) {
        try {
            return nextMethod(args, ctx);
        } catch (Exception e) {
            if (DEBUG_NEXT_METHOD) {
                System.out.println("DEBUG: next::can caught exception: " + e.getMessage());
            }
            return RuntimeScalarCache.scalarUndef.getList();
        }
    }

    public static RuntimeList maybeNextMethod(RuntimeArray args, int ctx) {
        try {
            RuntimeList canResult = nextCan(args, ctx);
            if (canResult.elements.isEmpty() || !canResult.elements.get(0).getDefinedBoolean()) {
                return RuntimeScalarCache.scalarUndef.getList();
            }
            return nextMethod(args, ctx);
        } catch (PerlCompilerException e) {
            return RuntimeScalarCache.scalarUndef.getList();
        }
    }
}
