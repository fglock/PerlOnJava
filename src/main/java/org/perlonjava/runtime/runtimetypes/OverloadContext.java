package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.runtime.mro.InheritanceResolver;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;

import java.util.function.Function;

import static org.perlonjava.runtime.runtimetypes.RuntimeContextType.SCALAR;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.*;

/**
 * Helper class to manage overloading context for a given scalar in Perl-style object system.
 * Handles method overloading and fallback mechanisms for blessed objects.
 *
 * <p><b>How Perl Overloading Works:</b>
 * <ul>
 *   <li>Classes use {@code use overload} to define operator overloads</li>
 *   <li>The overload pragma creates special methods in the class namespace:
 *     <ul>
 *       <li>{@code ((} - marker method indicating overload is enabled</li>
 *       <li>{@code ()} - fallback method</li>
 *       <li>{@code (0+} - numeric conversion method</li>
 *       <li>{@code ("")} - string conversion method</li>
 *       <li>etc. for other operators</li>
 *     </ul>
 *   </li>
 *   <li>When an overloaded operator is used, we:
 *     <ol>
 *       <li>Check if the object is blessed</li>
 *       <li>Look for {@code ((} method to see if overload is enabled</li>
 *       <li>Look for the specific operator method (e.g., {@code (0+})</li>
 *       <li>Try fallback mechanisms if direct method not found</li>
 *     </ol>
 *   </li>
 * </ul>
 *
 * <p><b>Math::BigInt Example:</b>
 * <pre>
 * package Math::BigInt;
 * use overload
 *     '0+' => sub { $_[0]->bstr() },  # Creates (0+ method
 *     '""' => \&bstr,                  # Creates ("" method
 *     # ... other operators
 *     ;
 * # The overload pragma also creates (( and () markers
 * </pre>
 *
 * @see InheritanceResolver#findMethodInHierarchy
 * @see Overload#numify
 * @see Overload#stringify
 */
public class OverloadContext {
    private static final boolean TRACE_OVERLOAD_CONTEXT = false;

    /**
     * The Perl class name of the blessed object
     */
    final String perlClassName;
    /**
     * The overloaded method handler
     */
    final RuntimeScalar methodOverloaded;
    /**
     * Whether the "()" fallback glob was found in the class hierarchy
     */
    final boolean hasFallbackGlob;
    /**
     * The fallback value from the SCALAR slot of the "()" glob.
     * Perl overload semantics:
     *   null (no glob found) or undef → allow autogeneration, die on failure
     *   true (1)                      → allow autogeneration, return undef on failure
     *   false (0)                     → deny autogeneration entirely
     */
    final RuntimeScalar fallbackValue;

    /**
     * Private constructor to create an OverloadContext instance.
     *
     * @param perlClassName    The Perl class name
     * @param methodOverloaded The overloaded method handler
     * @param hasFallbackGlob  Whether "()" glob was found
     * @param fallbackValue    The SCALAR slot value of "()" glob
     */
    private OverloadContext(String perlClassName, RuntimeScalar methodOverloaded, boolean hasFallbackGlob, RuntimeScalar fallbackValue) {
        this.perlClassName = perlClassName;
        this.methodOverloaded = methodOverloaded;
        this.hasFallbackGlob = hasFallbackGlob;
        this.fallbackValue = fallbackValue;
    }

    /**
     * Returns the Perl class name associated with this overload context.
     * Used by callers that need to produce Perl-style error messages
     * (e.g., {@code Operation "ne": no method found, left argument in
     * overloaded package X, ...}).
     */
    public String getPerlClassName() {
        return perlClassName;
    }

    /**
     * Whether this overload context permits fallback string/numeric
     * autogeneration for operations that aren't explicitly overloaded.
     * <p>
     * Perl's semantics:
     * <ul>
     *   <li>{@code fallback => 1}: autogeneration permitted → returns true</li>
     *   <li>{@code fallback => 0}: autogeneration denied → returns false</li>
     *   <li>{@code fallback => undef} (default): conservative, die on
     *       unable-to-autogen. We treat that as "not permitted" and let
     *       callers throw "no method found".</li>
     * </ul>
     * <p>
     * Used by binary operators (eq/ne/cmp/lt/gt) to decide whether a
     * fallback to stringification-based comparison is safe, or whether
     * the operation should throw "no method found" to match Perl 5.
     */
    public boolean allowsFallbackAutogen() {
        return hasFallbackGlob
                && fallbackValue != null
                && fallbackValue.getDefinedBoolean()
                && fallbackValue.getBoolean();
    }

    /**
     * Factory method to create overload context if applicable for a given RuntimeScalar.
     * Checks if the scalar is a blessed object and has overloading enabled.
     *
     * @param blessId Pointer to the class of the blessed object
     * @return OverloadContext instance if overloading is enabled, null otherwise
     */
    public static OverloadContext prepare(int blessId) {
        // Fast path: positive blessIds are non-overloaded classes (set at bless time)
        // Negative blessIds indicate classes with overloads
        // This saves ~10-20ns HashMap lookup per hash access
        if (blessId > 0) {
            return null;
        }

        // Check cache first
        OverloadContext cachedContext = InheritanceResolver.getCachedOverloadContext(blessId);
        if (cachedContext != null) {
            return cachedContext;
        }

        // Resolve Perl class name from blessing ID
        String perlClassName = NameNormalizer.getBlessStr(blessId);

        if (TRACE_OVERLOAD_CONTEXT) {
            System.err.println("TRACE OverloadContext.prepare:");
            System.err.println("  blessId: " + blessId);
            System.err.println("  perlClassName: " + perlClassName);
            System.err.flush();
        }

        // Look for overload markers in the class hierarchy
        RuntimeScalar methodOverloaded = InheritanceResolver.findMethodInHierarchy("((", perlClassName, null, 0);
        RuntimeScalar methodFallbackCode = InheritanceResolver.findMethodInHierarchy("()", perlClassName, null, 0);

        // Determine fallback value by reading the SCALAR slot of the "()" glob.
        // Perl's overload.pm stores: CODE slot = \&overload::nil (marker), SCALAR slot = fallback value
        boolean hasFallbackGlob = (methodFallbackCode != null);
        RuntimeScalar fallbackValue = null;
        if (hasFallbackGlob) {
            java.util.List<String> linearizedClasses = InheritanceResolver.linearizeHierarchy(perlClassName);
            for (String className : linearizedClasses) {
                String effectiveClassName = GlobalVariable.resolveStashAlias(className);
                String normalizedName = NameNormalizer.normalizeVariableName("()", effectiveClassName);
                if (GlobalVariable.existsGlobalCodeRef(normalizedName)) {
                    fallbackValue = GlobalVariable.getGlobalVariable(normalizedName);
                    break;
                }
            }
        }

        if (TRACE_OVERLOAD_CONTEXT) {
            System.err.println("  methodOverloaded ((): " + (methodOverloaded != null ? "FOUND" : "NULL"));
            System.err.println("  hasFallbackGlob (): " + hasFallbackGlob);
            System.err.println("  fallbackValue: " + (fallbackValue != null ? fallbackValue.toString() : "null"));
            System.err.flush();
        }

        // Create context if overloading is enabled
        OverloadContext context = null;
        if (methodOverloaded != null || hasFallbackGlob) {
            context = new OverloadContext(perlClassName, methodOverloaded, hasFallbackGlob, fallbackValue);
            // Cache the result
            InheritanceResolver.cacheOverloadContext(blessId, context);
        }

        return context;
    }

    public static RuntimeScalar tryOneArgumentOverload(RuntimeScalar runtimeScalar, int blessId, String operator, String methodName, Function<RuntimeScalar, RuntimeScalar> fallbackFunction) {
        // Prepare overload context and check if object is eligible for overloading
        OverloadContext ctx = OverloadContext.prepare(blessId);
        if (ctx == null) return null;
        // Try primary overload method
        RuntimeScalar result = ctx.tryOverload(operator, new RuntimeArray(runtimeScalar));
        if (result != null) return result;
        // Try fallback
        result = ctx.tryOverloadFallback(runtimeScalar, "(0+", "(\"\"", "(bool");
        if (result != null) {
            return fallbackFunction.apply(result);
        }
        // Try nomethod
        result = ctx.tryOverloadNomethod(runtimeScalar, methodName);
        return result;
    }

    public static RuntimeScalar tryTwoArgumentOverload(RuntimeScalar arg1, RuntimeScalar arg2, int blessId, int blessId2, String overloadName, String methodName) {
        return tryTwoArgumentOverload(arg1, arg2, blessId, blessId2, overloadName, methodName, (String[]) null);
    }

    /**
     * Tries overloaded binary operator with autogeneration support.
     * @param autogenNames Additional overload names to try as autogeneration candidates (e.g., "(+" for "(+=")
     */
    public static RuntimeScalar tryTwoArgumentOverload(RuntimeScalar arg1, RuntimeScalar arg2, int blessId, int blessId2, String overloadName, String methodName, String... autogenNames) {
        OverloadContext ctx1 = null;
        OverloadContext ctx2 = null;

        if (blessId < 0) {
            // Try primary overload method
            ctx1 = prepare(blessId);
            if (ctx1 != null) {
                RuntimeScalar result = ctx1.tryOverload(overloadName, new RuntimeArray(arg1, arg2, scalarFalse));
                if (result != null) return result;
            }
        }
        if (blessId2 < 0) {
            // Try swapped overload
            ctx2 = prepare(blessId2);
            if (ctx2 != null) {
                RuntimeScalar result = ctx2.tryOverload(overloadName, new RuntimeArray(arg2, arg1, scalarTrue));
                if (result != null) return result;
            }
        }

        // Try autogeneration: try alternative operator names (e.g., "+" for "+=")
        if (autogenNames != null) {
            for (String autogenName : autogenNames) {
                if (autogenName == null) continue;
                if (ctx1 != null) {
                    RuntimeScalar result = ctx1.tryOverload(autogenName, new RuntimeArray(arg1, arg2, scalarFalse));
                    if (result != null) return result;
                }
                if (ctx2 != null) {
                    RuntimeScalar result = ctx2.tryOverload(autogenName, new RuntimeArray(arg2, arg1, scalarTrue));
                    if (result != null) return result;
                }
            }
        }

        if (ctx1 != null) {
            // Try first nomethod
            RuntimeScalar result = ctx1.tryOverload("(nomethod", new RuntimeArray(arg1, arg2, scalarFalse, new RuntimeScalar(methodName)));
            if (result != null) return result;
        }
        if (ctx2 != null) {
            // Try swapped nomethod
            RuntimeScalar result = ctx2.tryOverload("(nomethod", new RuntimeArray(arg2, arg1, scalarTrue, new RuntimeScalar(methodName)));
            if (result != null) return result;
        }

        // All overload attempts failed. Check fallback semantics.
        // Perl overload fallback rules:
        //   fallback=0 (explicitly false): deny autogeneration, die immediately
        //   fallback=undef/not specified:  allow autogeneration, die only if autogeneration also fails
        //   fallback=1 (true):             allow autogeneration, use native op if all else fails
        //
        // Since callers (e.g., CompareOperators) handle autogeneration by calling us again
        // with the spaceship/cmp operator, we return null here to allow that attempt.
        // Only fallback=0 should block autogeneration and die immediately.
        OverloadContext activeCtx = (ctx1 != null) ? ctx1 : ctx2;
        if (activeCtx != null) {
            // Check if fallback is explicitly false (fallback => 0): deny autogeneration, die
            if (activeCtx.hasFallbackGlob && activeCtx.fallbackValue != null
                    && activeCtx.fallbackValue.getDefinedBoolean() && !activeCtx.fallbackValue.getBoolean()) {
                String className = activeCtx.perlClassName;
                throw new PerlCompilerException("Operation \"" + methodName + "\": no method found, "
                        + "argument in overloaded package " + className);
            }
            // fallback not specified, undef, or true: allow autogeneration / native fallback
            return null;
        }
        return null;
    }

    public RuntimeScalar tryOverloadNomethod(RuntimeScalar runtimeScalar, String methodName) {
        return tryOverload("(nomethod", new RuntimeArray(runtimeScalar, scalarUndef, scalarUndef, new RuntimeScalar(methodName)));
    }

    /**
     * Attempts to execute fallback overloading methods if primary method fails.
     * Implements Perl 5 autogeneration semantics based on the fallback value:
     *   - No "()" glob found:       treat as fallback=undef → allow autogeneration
     *   - fallback=undef:           allow autogeneration, die on failure
     *   - fallback=1 (true):        allow autogeneration, return undef on failure
     *   - fallback=0 (false):       deny autogeneration entirely
     *
     * @param runtimeScalar   The scalar value to process
     * @param fallbackMethods Variable number of fallback method names to try in sequence
     * @return RuntimeScalar result from successful fallback execution, or null if all attempts fail
     */
    public RuntimeScalar tryOverloadFallback(RuntimeScalar runtimeScalar, String... fallbackMethods) {
        // Check if autogeneration is explicitly denied (fallback => 0)
        if (hasFallbackGlob && fallbackValue != null && fallbackValue.getDefinedBoolean() && !fallbackValue.getBoolean()) {
            return null;
        }

        // All other cases: try autogeneration
        // (no glob found, fallback=undef, fallback=1)
        for (String fallbackMethod : fallbackMethods) {
            RuntimeScalar result = this.tryOverload(fallbackMethod, new RuntimeArray(runtimeScalar));
            if (result != null) return result;
        }
        return null;
    }

    /**
     * Attempts to execute an overloaded method with given arguments.
     * Handles TAILCALL markers from `goto $coderef` with a trampoline loop.
     *
     * <p>Perl's overload pragma allows specifying methods by name (string) instead of
     * code reference. When this is done, the CODE slot of the glob contains {@code \&overload::nil}
     * and the SCALAR slot contains the method name. This method handles both cases:
     * <ol>
     *   <li>Direct code reference: execute it immediately</li>
     *   <li>Method name (via overload::nil): look up the SCALAR slot to get the method name,
     *       then resolve and call the actual method</li>
     * </ol>
     *
     * @param methodName     The name of the method to execute
     * @param perlMethodArgs Array of arguments to pass to the method
     * @return RuntimeScalar result from method execution, or null if method not found
     */
    public RuntimeScalar tryOverload(String methodName, RuntimeArray perlMethodArgs) {
        // Look for method in class hierarchy
        RuntimeScalar perlMethod = InheritanceResolver.findMethodInHierarchy(methodName, perlClassName, null, 0);
        if (perlMethod == null) {
            return null;
        }
        
        // Check if this is overload::nil (indicates method name is in SCALAR slot)
        // Perl's overload.pm stores method names in the SCALAR slot when a string is passed:
        //   use overload '""' => '_stringify';  # stores "_stringify" in SCALAR, \&nil in CODE
        if (perlMethod.value instanceof RuntimeCode) {
            RuntimeCode code = (RuntimeCode) perlMethod.value;
            if ("nil".equals(code.subName) && "overload".equals(code.packageName)) {
                // Found overload::nil - look up the actual method name from SCALAR slot
                perlMethod = resolveOverloadMethodName(methodName, perlMethodArgs);
                if (perlMethod == null) {
                    return null;
                }
            }
        }
        
        // Execute found method with provided arguments
        RuntimeList result = RuntimeCode.apply(perlMethod, perlMethodArgs, SCALAR);
        
        // Handle TAILCALL markers from `goto $coderef` with trampoline loop
        while (result instanceof RuntimeControlFlowList) {
            RuntimeControlFlowList flow = (RuntimeControlFlowList) result;
            if (flow.getControlFlowType() == ControlFlowType.TAILCALL) {
                // Execute the tail call
                RuntimeScalar codeRef = flow.getTailCallCodeRef();
                RuntimeArray args = flow.getTailCallArgs();
                result = RuntimeCode.apply(codeRef, args, SCALAR);
            } else {
                // Not a TAILCALL - other control flow types (LAST/NEXT/REDO/GOTO)
                // should propagate up, but for overload context we just return the first element
                break;
            }
        }
        
        return result.getFirst();
    }
    
    /**
     * Resolves an overload method name stored in the SCALAR slot of the glob.
     * When Perl's overload pragma is given a method name string (not a code ref),
     * it stores the method name in the SCALAR slot and \&nil in the CODE slot.
     *
     * <p>The SCALAR slot can contain:
     * <ul>
     *   <li>A method name string (e.g., "_stringify")</li>
     *   <li>A glob reference (e.g., "*Package::Method") pointing to another glob</li>
     * </ul>
     *
     * @param methodName     The overload method name (e.g., "(\"\"")
     * @param perlMethodArgs The arguments (first should be the object to call the method on)
     * @return RuntimeScalar representing the resolved method, or null if not found
     */
    private RuntimeScalar resolveOverloadMethodName(String methodName, RuntimeArray perlMethodArgs) {
        // Get the SCALAR slot value which contains the actual method name
        // Walk the class hierarchy to find the glob with the method name
        java.util.List<String> linearizedClasses = InheritanceResolver.linearizeHierarchy(perlClassName);
        
        for (String className : linearizedClasses) {
            String effectiveClassName = GlobalVariable.resolveStashAlias(className);
            String normalizedGlobName = NameNormalizer.normalizeVariableName(methodName, effectiveClassName);
            
            // Check if this class has the overload glob
            if (GlobalVariable.existsGlobalCodeRef(normalizedGlobName)) {
                // Get the SCALAR slot of this glob
                RuntimeScalar scalarSlot = GlobalVariable.getGlobalVariable(normalizedGlobName);
                if (scalarSlot != null && scalarSlot.getDefinedBoolean()) {
                    String actualMethodName = scalarSlot.toString();
                    
                    // If the scalar is a glob reference (starts with *), follow it
                    // The glob reference points to another package's overload glob
                    // e.g., "*Specio::Constraint::Role::Interface::(\"\"" 
                    // which itself has "_stringify" in its SCALAR slot
                    while (actualMethodName.startsWith("*")) {
                        // Parse the full glob name: *Package::Name::(""
                        // Remove the leading *
                        String globFullName = actualMethodName.substring(1);
                        
                        // Get the SCALAR slot of the referenced glob
                        scalarSlot = GlobalVariable.getGlobalVariable(globFullName);
                        if (scalarSlot == null || !scalarSlot.getDefinedBoolean()) {
                            return null;
                        }
                        actualMethodName = scalarSlot.toString();
                    }
                    
                    // The actual method name - find it using can() semantics
                    // (search in the object's class hierarchy)
                    RuntimeScalar resolved = InheritanceResolver.findMethodInHierarchy(actualMethodName, perlClassName, null, 0);
                    // If AUTOLOAD was resolved instead of the actual method, set $AUTOLOAD
                    if (resolved != null && resolved.value instanceof RuntimeCode) {
                        RuntimeCode code = (RuntimeCode) resolved.value;
                        if (code.autoloadVariableName != null) {
                            String fullMethodName = NameNormalizer.normalizeVariableName(actualMethodName, perlClassName);
                            GlobalVariable.getGlobalVariable(code.autoloadVariableName).set(fullMethodName);
                        }
                    }
                    return resolved;
                }
            }
        }
        
        return null;
    }
}
