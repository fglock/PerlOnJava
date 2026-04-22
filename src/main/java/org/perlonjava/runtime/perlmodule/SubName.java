package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarFalse;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarTrue;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarType.*;

/**
 * Sub::Name module implementation for PerlOnJava.
 * Provides the subname() function to (re)name a subroutine.
 * 
 * This is compatible with the CPAN Sub::Name module.
 * The subname is only used for informative routines (caller, Carp, etc).
 * 
 * @see <a href="https://metacpan.org/pod/Sub::Name">Sub::Name on CPAN</a>
 */
public class SubName extends PerlModuleBase {

    /**
     * Constructor for SubName.
     */
    public SubName() {
        super("Sub::Name", false);  // false because loaded via XSLoader
    }

    /**
     * Static initializer called by XSLoader::load().
     */
    public static void initialize() {
        SubName subName = new SubName();
        subName.initializeExporter();
        // Set $VERSION to match CPAN version
        GlobalVariable.getGlobalVariable("Sub::Name::VERSION").set(new RuntimeScalar("0.28"));
        // subname is exported by default
        subName.defineExport("EXPORT", "subname");
        subName.defineExport("EXPORT_OK", "subname");
        try {
            subName.registerMethod("subname", null);  // No prototype to allow flexible args
            // Private helper used by B.pm to detect CVs renamed via Sub::Name::subname.
            subName.registerMethod("_is_renamed", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Sub::Name method: " + e.getMessage());
        }
    }

    /**
     * subname NAME, CODEREF
     * 
     * Assigns a new name to referenced sub. If package specification is omitted in
     * the name, then the current package is used. The return value is the sub.
     * 
     * The name is only used for informative routines (caller, Carp, etc). You won't
     * be able to actually invoke the sub by the given name. To allow that, you need
     * to do glob-assignment yourself.
     *
     * @param args The arguments: name string, CODE reference
     * @param ctx  The context
     * @return The CODE reference
     */
    public static RuntimeList subname(RuntimeArray args, int ctx) {
        if (args.size() != 2) {
            throw new IllegalStateException("Usage: subname(name, coderef)");
        }
        RuntimeScalar nameScalar = args.get(0);
        RuntimeScalar codeRef = args.get(1);
        
        if (codeRef.type != CODE) {
            throw new IllegalArgumentException("Not a subroutine reference");
        }
        
        RuntimeCode code = (RuntimeCode) codeRef.value;
        String fullName = nameScalar.toString();
        
        // Parse package::subname format
        int lastColon = fullName.lastIndexOf("::");
        if (lastColon >= 0) {
            code.packageName = fullName.substring(0, lastColon);
            code.subName = fullName.substring(lastColon + 2);
        } else {
            // No package specified - use current package from caller
            RuntimeList callerInfo = RuntimeCode.caller(new RuntimeList(), RuntimeContextType.LIST);
            if (!callerInfo.isEmpty()) {
                code.packageName = callerInfo.elements.get(0).toString();
            }
            code.subName = fullName;
        }
        // Mark the CV as explicitly renamed so B::svref_2object()->GV->NAME
        // honors the assigned name even when no matching stash entry exists.
        code.explicitlyRenamed = true;
        return codeRef.getList();
    }

    /**
     * _is_renamed CODEREF
     *
     * Private helper for B.pm. Returns a true scalar if the given code
     * reference has been explicitly renamed via Sub::Name::subname (or
     * Sub::Util::set_subname), otherwise an empty/false scalar. Non-CODE
     * arguments yield false.
     */
    public static RuntimeList _is_renamed(RuntimeArray args, int ctx) {
        if (args.size() != 1) {
            return scalarFalse.getList();
        }
        RuntimeScalar ref = args.get(0);
        if (ref.type != CODE) {
            return scalarFalse.getList();
        }
        RuntimeCode code = (RuntimeCode) ref.value;
        return (code.explicitlyRenamed ? scalarTrue : scalarFalse).getList();
    }
}
