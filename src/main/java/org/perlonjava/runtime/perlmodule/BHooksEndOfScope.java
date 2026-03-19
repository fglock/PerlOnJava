package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

/**
 * Java XS implementation for B::Hooks::EndOfScope.
 * 
 * Uses PerlOnJava's defer mechanism (DeferBlock + DynamicVariableManager)
 * to implement scope-end callbacks.
 * 
 * B::Hooks::EndOfScope provides on_scope_end() which registers a callback
 * to execute when the current scope exits. This is used by modules like
 * namespace::autoclean to clean up imported functions.
 */
public class BHooksEndOfScope extends PerlModuleBase {

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
     * Registers a callback to be executed when the current scope exits.
     * 
     * Usage in Perl:
     *   use B::Hooks::EndOfScope;
     *   on_scope_end { print "scope ended\n" };
     * 
     * The callback is executed in LIFO order with other defer blocks
     * when the scope is exited (via normal flow, return, die, etc.)
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
        
        // Create a DeferBlock and push it onto the dynamic variable stack
        // This will cause the callback to be executed when the scope exits
        DeferBlock deferBlock = new DeferBlock(codeRef);
        DynamicVariableManager.pushLocalVariable(deferBlock);
        
        return new RuntimeList();
    }
}
