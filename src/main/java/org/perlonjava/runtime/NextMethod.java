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

    /**
     * Implements next::method functionality.
     * Finds and calls the next method in the C3 linearization.
     *
     * @param args The arguments to pass to the next method.
     * @param ctx  The context in which the method is called.
     * @return The result of calling the next method.
     * @throws PerlCompilerException if no next method is found.
     */
    public static RuntimeList nextMethod(RuntimeArray args, int ctx) {
        // Get caller information using the existing caller() method
        RuntimeList callerInfo = RuntimeCode.caller(new RuntimeList(), RuntimeContextType.LIST);

        if (callerInfo.elements.size() < 1) {
            throw new PerlCompilerException("next::method called outside a method");
        }

        String callerPackage = callerInfo.elements.get(0).toString();

        // Get the calling subroutine name from level 1
        RuntimeList level1Args = scalarOne.getList();
        RuntimeList subInfo = RuntimeCode.caller(level1Args, RuntimeContextType.LIST);

        String subroutineName = "";
        if (subInfo.elements.size() > 3) {
            subroutineName = subInfo.elements.get(3).toString(); // subroutine name is at index 3
        }

        // Extract just the method name if it includes package
        String methodName = subroutineName;
        if (methodName.isEmpty()) {
            // Try to get from current context if available
            throw new PerlCompilerException("Can't resolve method name for next::method");
        }

        int lastDoubleColon = methodName.lastIndexOf("::");
        if (lastDoubleColon >= 0) {
            callerPackage = methodName.substring(0, lastDoubleColon);
            methodName = methodName.substring(lastDoubleColon + 2);
        }

        if (methodName.isEmpty()) {
            throw new PerlCompilerException("Can't resolve method name for next::method");
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

        // Get C3 linearization
        List<String> linearized = C3.linearizeC3(searchClass);

        // Find the calling class in the linearization
        int callerIndex = linearized.indexOf(callerPackage);
        if (callerIndex == -1) {
            throw new PerlCompilerException("Can't find calling class '" + callerPackage + "' in method resolution order for " + searchClass);
        }

        // Search for the next method starting after the calling class
        RuntimeScalar nextMethod = InheritanceResolver.findMethodInHierarchy(
                methodName, searchClass, null, callerIndex + 1
        );

        if (nextMethod == null || !nextMethod.getDefinedBoolean()) {
            throw new PerlCompilerException("No next::method '" + methodName + "' found for " + searchClass);
        }

        // Call the next method - don't shift the arguments since we need to pass them as-is
        return RuntimeCode.call(firstArg, nextMethod, null, args, ctx);
    }

    /**
     * Implements next::can functionality.
     * Returns a code reference to the next method, or undef if not found.
     *
     * @param args The arguments (should contain the object/class).
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing the code reference or undef.
     */
    public static RuntimeList nextCan(RuntimeArray args, int ctx) {
        try {
            // Get caller information using the existing caller() method
            RuntimeList callerInfo = RuntimeCode.caller(new RuntimeList(), RuntimeContextType.LIST);

            if (callerInfo.elements.size() < 1) {
                return RuntimeScalarCache.scalarUndef.getList();
            }

            String callerPackage = callerInfo.elements.get(0).toString();

            // Get the calling subroutine name from level 1
            RuntimeList level1Args = scalarOne.getList();
            RuntimeList subInfo = RuntimeCode.caller(level1Args, RuntimeContextType.LIST);

            String subroutineName = "";
            if (subInfo.elements.size() > 3) {
                subroutineName = subInfo.elements.get(3).toString(); // subroutine name is at index 3
            }

            // Extract just the method name if it includes package
            String methodName = subroutineName;
            if (methodName.isEmpty()) {
                return RuntimeScalarCache.scalarUndef.getList();
            }

            int lastDoubleColon = methodName.lastIndexOf("::");
            if (lastDoubleColon >= 0) {
                callerPackage = methodName.substring(0, lastDoubleColon);
                methodName = methodName.substring(lastDoubleColon + 2);
            }

            if (methodName.isEmpty()) {
                return RuntimeScalarCache.scalarUndef.getList();
            }

            // Get the object/class from the first argument
            if (args.size() == 0) {
                return RuntimeScalarCache.scalarUndef.getList();
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
                        return RuntimeScalarCache.scalarUndef.getList();
                    }
                    searchClass = NameNormalizer.getBlessStr(blessId);
                    break;
                default:
                    searchClass = firstArg.toString();
                    if (searchClass.isEmpty()) {
                        return RuntimeScalarCache.scalarUndef.getList();
                    }
            }

            // Get C3 linearization
            List<String> linearized = C3.linearizeC3(searchClass);

            // Find the calling class in the linearization
            int callerIndex = linearized.indexOf(callerPackage);
            if (callerIndex == -1) {
                return RuntimeScalarCache.scalarUndef.getList();
            }

            // Search for the next method starting after the calling class
            RuntimeScalar nextMethod = InheritanceResolver.findMethodInHierarchy(
                    methodName, searchClass, null, callerIndex + 1
            );

            if (nextMethod == null || !nextMethod.getDefinedBoolean()) {
                return RuntimeScalarCache.scalarUndef.getList();
            }

            return nextMethod.getList();
        } catch (Exception e) {
            return RuntimeScalarCache.scalarUndef.getList();
        }
    }

    /**
     * Implements maybe::next::method functionality.
     * Calls next::method if it exists, otherwise returns undef.
     *
     * @param args The arguments to pass to the next method.
     * @param ctx  The context in which the method is called.
     * @return The result of calling the next method, or undef if not found.
     */
    public static RuntimeList maybeNextMethod(RuntimeArray args, int ctx) {
        try {
            // First check if next method exists using next::can logic
            RuntimeList canResult = nextCan(args, ctx);
            if (canResult.elements.isEmpty() || !canResult.elements.get(0).getDefinedBoolean()) {
                return RuntimeScalarCache.scalarUndef.getList();
            }

            // If it exists, call next::method
            return nextMethod(args, ctx);
        } catch (PerlCompilerException e) {
            // If next::method throws an exception, return undef
            return RuntimeScalarCache.scalarUndef.getList();
        }
    }
}