package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;
import org.perlonjava.runtime.CompilationRuntimeState;

import java.util.*;

/**
 * Java XS implementation for B::Hooks::EndOfScope.
 * 
 * Implements compile-time scope-end callbacks for modules like namespace::clean
 * and namespace::autoclean. When a file uses on_scope_end, the callback fires
 * when that file finishes loading (compilation), not when the current function
 * returns.
 * 
 * This differs from Perl's runtime scope exit - it specifically handles the
 * compile-time use case that namespace::clean requires.
 */
public class BHooksEndOfScope extends PerlModuleBase {

    /**
     * Registry of callbacks keyed by the file that registered them.
     * When a file finishes loading, callbacks registered for that file are fired in LIFO order.
     */
    private static CompilationRuntimeState state() {
        return PerlRuntime.current().compilationState;
    }

    public BHooksEndOfScope() {
        super("B::Hooks::EndOfScope", false);
    }

    /**
     * Static initializer called by XSLoader.
     */
    public static void initialize() {
        BHooksEndOfScope module = new BHooksEndOfScope();
        try {
            // Prototype "&" means first argument is a code block
            module.registerMethod("on_scope_end", "on_scope_end", "&");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing B::Hooks::EndOfScope method: " + e.getMessage());
        }
    }
    
    /**
     * Called by ModuleOperators.doFile before executing a file.
     * Pushes the filename onto the loading stack.
     * If fileName is null (e.g., when loading from a filehandle), this is a no-op.
     */
    public static void beginFileLoad(String fileName) {
        if (fileName != null) {
            state().loadingFileStack.push(fileName);
        }
    }
    
    /**
     * Called by ModuleOperators.doFile after executing a file.
     * Pops the filename from the loading stack and fires any registered callbacks.
     * If fileName is null (e.g., when loading from a filehandle), this is a no-op.
     */
    public static void endFileLoad(String fileName) {
        if (fileName == null) {
            return;
        }
        
        Deque<String> stack = state().loadingFileStack;
        if (!stack.isEmpty() && stack.peek().equals(fileName)) {
            stack.pop();
        }
        
        // Fire callbacks registered for this file in LIFO order.
        //
        // Save and restore $@ / $! around the callback execution: callbacks
        // such as namespace::autoclean's cleanup routine internally use
        // `eval { ... }` blocks, which Perl resets `$@` to "" on success.
        // If a `use Foo;` inside the file being loaded threw a
        // "Can't locate Foo.pm in @INC" error, doFile's catch block has
        // already stored that message in $@ and we must NOT let scope-end
        // hooks clobber it - otherwise the outer `require` reports a
        // misleading "Can't locate <outer-file>.pm" instead of the real
        // inner cause. (Reproducible with `jcpan -t Text::WordCounter`.)
        Deque<RuntimeScalar> callbacks = state().endOfScopeFileCallbacks.remove(fileName);
        if (callbacks != null) {
            String savedErr = GlobalVariable.getGlobalVariable("main::@").toString();
            String savedBang = GlobalVariable.getGlobalVariable("main::!").toString();
            try {
                while (!callbacks.isEmpty()) {
                    RuntimeScalar codeRef = callbacks.pop();
                    try {
                        if (codeRef.type == RuntimeScalarType.CODE && codeRef.value instanceof RuntimeCode code) {
                            code.apply(new RuntimeArray(), RuntimeContextType.VOID);
                        }
                    } catch (Exception e) {
                        // Log but don't propagate - callbacks shouldn't break file loading
                        System.err.println("Warning: on_scope_end callback error: " + e.getMessage());
                    }
                }
            } finally {
                GlobalVariable.setGlobalVariable("main::@", savedErr);
                GlobalVariable.setGlobalVariable("main::!", savedBang);
            }
        }
    }
    
    /**
     * Get the current file being loaded (top of stack), or null if not in a file load.
     */
    private static String getCurrentLoadingFile() {
        Deque<String> stack = state().loadingFileStack;
        return stack.isEmpty() ? null : stack.peek();
    }

    /** Enter a parser-visible lexical scope. */
    public static void beginCompileScope() {
        state().compileScopes.push(new ArrayDeque<>());
    }

    /**
     * Leave a parser-visible lexical scope and run callbacks registered in it
     * in LIFO order, matching the native hook's ordering.
     */
    public static void endCompileScope() {
        Deque<Deque<RuntimeScalar>> scopes = state().compileScopes;
        if (scopes.isEmpty()) {
            return;
        }
        Deque<RuntimeScalar> callbacks = scopes.pop();
        while (!callbacks.isEmpty()) {
            RuntimeScalar codeRef = callbacks.pop();
            try {
                if (codeRef.type == RuntimeScalarType.CODE && codeRef.value instanceof RuntimeCode code) {
                    code.apply(new RuntimeArray(), RuntimeContextType.VOID);
                }
            } catch (Exception e) {
                System.err.println("Warning: on_scope_end callback error: " + e.getMessage());
            }
        }
    }

    /**
     * Registers a callback to be executed when the calling file finishes loading.
     * 
     * Usage in Perl:
     *   use B::Hooks::EndOfScope;
     *   on_scope_end { print "scope ended\n" };
     * 
     * The callback is executed in LIFO order when the file that called on_scope_end
     * finishes loading (i.e., when require/do returns).
     * 
     * @param args The arguments: args[0] is the code reference (callback)
     * @param ctx The runtime context
     * @return Empty list (void context)
     */
    public static RuntimeList on_scope_end(RuntimeArray args, int ctx) {
        if (args.size() < 1) {
            throw new RuntimeException("on_scope_end requires a code reference");
        }
        
        RuntimeScalar codeRef = args.get(0);
        
        // Verify it's a code reference
        if (codeRef.type != RuntimeScalarType.CODE) {
            throw new RuntimeException("on_scope_end requires a code reference, got " + codeRef.type);
        }
        
        // Prefer the innermost parser-visible lexical scope.  This is the
        // behavior required by namespace::clean for nested blocks.
        Deque<Deque<RuntimeScalar>> scopes = state().compileScopes;
        if (!scopes.isEmpty()) {
            scopes.peek().push(codeRef);
            return new RuntimeList();
        }

        // Find which file is currently being loaded
        String currentFile = getCurrentLoadingFile();
        
        if (currentFile != null) {
            // Register callback for end of file load
            state().endOfScopeFileCallbacks.computeIfAbsent(currentFile, k -> new ArrayDeque<>()).push(codeRef);
        } else {
            // Fallback: if not in a file load context (e.g., main script or eval),
            // use defer mechanism for immediate scope exit
            DeferBlock deferBlock = new DeferBlock(codeRef);
            DynamicVariableManager.pushLocalVariable(deferBlock);
        }
        
        return new RuntimeList();
    }
}
