package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.*;

/**
 * Sub::Util module implementation for PerlOnJava.
 * Provides utility functions for working with subroutines.
 */
public class SubUtil extends PerlModuleBase {

    /**
     * Constructor for SubUtil.
     */
    public SubUtil() {
        super("Sub::Util");
    }

    /**
     * Static initializer to set up the Sub::Util module.
     */
    public static void initialize() {
        SubUtil subUtil = new SubUtil();
        subUtil.initializeExporter();
        // Set $VERSION so CPAN.pm can detect our bundled version
        GlobalVariable.getGlobalVariable("Sub::Util::VERSION").set(new RuntimeScalar("1.70"));
        subUtil.defineExport("EXPORT_OK", "prototype", "set_prototype", "subname", "set_subname");
        try {
            subUtil.registerMethod("prototype", "$");
            subUtil.registerMethod("set_prototype", null);  // No prototype to allow @_ passing
            subUtil.registerMethod("subname", "$");
            subUtil.registerMethod("set_subname", null);  // No prototype to allow @_ passing
            // Phase D-W2c: B.pm consults `Sub::Name::_is_renamed` to know
            // whether to honor a Sub::Util::set_subname rename in
            // `B::CV->GV->NAME`. Expose `Sub::Util::_is_renamed` (and
            // alias `Sub::Name::_is_renamed`) so set_subname is reflected
            // by Class::MOP::get_code_info even if the renamed sub was
            // never installed into the target package's stash.
            subUtil.registerMethod("_is_renamed", "$");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Sub::Util method: " + e.getMessage());
        }
    }

    /**
     * Returns the prototype of a subroutine.
     *
     * @param args The arguments: a CODE reference
     * @param ctx  The context
     * @return The prototype string or undef
     */
    public static RuntimeList prototype(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for prototype()");
        }
        RuntimeScalar codeRef = args.get(0);
        if (codeRef.type != CODE) {
            return new RuntimeScalar().getList(); // undef for non-CODE
        }
        RuntimeCode code = (RuntimeCode) codeRef.value;
        String proto = code.prototype;
        if (proto == null) {
            return new RuntimeScalar().getList(); // undef
        }
        return new RuntimeScalar(proto).getList();
    }

    /**
     * Sets the prototype of a subroutine.
     *
     * @param args The arguments: prototype string, CODE reference
     * @param ctx  The context
     * @return The CODE reference
     */
    public static RuntimeList set_prototype(RuntimeArray args, int ctx) {
        if (args.size() != 2) {
            throw new IllegalStateException("Bad number of arguments for set_prototype()");
        }
        RuntimeScalar protoScalar = args.get(0);
        RuntimeScalar codeRef = args.get(1);
        
        if (codeRef.type != CODE) {
            throw new IllegalArgumentException("set_prototype requires a CODE reference");
        }
        
        RuntimeCode code = (RuntimeCode) codeRef.value;
        if (protoScalar.type == UNDEF) {
            code.prototype = null;
        } else {
            code.prototype = protoScalar.toString();
        }
        return codeRef.getList();
    }

    /**
     * Returns the name of a subroutine.
     *
     * @param args The arguments: a CODE reference
     * @param ctx  The context
     * @return The name of the subroutine
     */
    public static RuntimeList subname(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for subname()");
        }
        RuntimeScalar codeRef = args.get(0);
        if (codeRef.type != CODE) {
            return new RuntimeScalar().getList(); // undef for non-CODE
        }
        RuntimeCode code = (RuntimeCode) codeRef.value;
        String pkg = code.packageName;
        String sub = code.subName;
        if (sub == null || sub.isEmpty()) {
            // Anonymous sub: real Perl returns "Package::__ANON__" where Package
            // is the compile-time package (CvSTASH).
            if (pkg != null && !pkg.isEmpty()) {
                return new RuntimeScalar(pkg + "::__ANON__").getList();
            }
            return new RuntimeScalar("__ANON__").getList();
        }
        if (pkg != null && !pkg.isEmpty()) {
            return new RuntimeScalar(pkg + "::" + sub).getList();
        }
        return new RuntimeScalar(sub).getList();
    }

    /**
     * Sets the name of a subroutine.
     *
     * @param args The arguments: name string, CODE reference
     * @param ctx  The context
     * @return The CODE reference
     */
    public static RuntimeList set_subname(RuntimeArray args, int ctx) {
        if (args.size() != 2) {
            throw new IllegalStateException("Bad number of arguments for set_subname()");
        }
        RuntimeScalar nameScalar = args.get(0);
        RuntimeScalar codeRef = args.get(1);
        
        if (codeRef.type != CODE) {
            throw new IllegalArgumentException("set_subname requires a CODE reference");
        }
        
        RuntimeCode code = (RuntimeCode) codeRef.value;
        String fullName = nameScalar.toString();
        
        // Parse package::subname format
        int lastColon = fullName.lastIndexOf("::");
        if (lastColon >= 0) {
            code.packageName = fullName.substring(0, lastColon);
            code.subName = fullName.substring(lastColon + 2);
        } else {
            code.subName = fullName;
        }
        // Mark the CV as explicitly renamed so B::svref_2object()->GV->NAME
        // honors the assigned name even when no matching stash entry exists.
        code.explicitlyRenamed = true;
        return codeRef.getList();
    }

    /**
     * Phase D-W2c: returns true if {@code set_subname} has been called on
     * the given coderef. Used by {@code B::CV->_introspect} to decide
     * whether to honor the renamed name.
     */
    public static RuntimeList _is_renamed(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            throw new IllegalStateException("Bad number of arguments for _is_renamed()");
        }
        RuntimeScalar codeRef = args.get(0);
        if (codeRef.type != CODE) return new RuntimeScalar(0).getList();
        RuntimeCode code = (RuntimeCode) codeRef.value;
        return new RuntimeScalar(code.explicitlyRenamed ? 1 : 0).getList();
    }
}
