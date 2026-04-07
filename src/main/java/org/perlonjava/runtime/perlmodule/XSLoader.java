package org.perlonjava.runtime.perlmodule;

import org.perlonjava.backend.bytecode.EvalStringHandler;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.*;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.perlonjava.runtime.runtimetypes.RuntimeContextType.SCALAR;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarTrue;

public class XSLoader extends PerlModuleBase {

    /** Guard against recursive loadJarShimOverrides calls (e.g. Clone.pm evals XSLoader::load). */
    private static final Set<String> shimLoadingInProgress = new HashSet<>();

    /**
     * Non-functional base classes that should be ignored when checking @ISA
     * for pure-Perl fallback parents. These classes provide infrastructure
     * (exporting, autoloading) but not the module's actual functionality.
     * A module with only these in @ISA should NOT get the silent success
     * treatment — it needs XS or its own pure-Perl fallback code.
     */
    private static final Set<String> NON_FUNCTIONAL_ISA = Set.of(
            "Exporter",
            "DynaLoader",
            "AutoLoader",
            "XSLoader"
    );

    /**
     * Constructor for XSLoader.
     * Initializes the module with the name "XSLoader".
     */
    public XSLoader() {
        super("XSLoader", true);
    }

    /**
     * Static initializer to set up the XSLoader module.
     */
    public static void initialize() {
        XSLoader xsLoader = new XSLoader();
        try {
            xsLoader.registerMethod("load", null);
            xsLoader.registerMethod("bootstrap_inherit", null);
            
            // Set $XSLoader::VERSION to match the CPAN version we're compatible with
            GlobalVariable.getGlobalVariable("XSLoader::VERSION").set("0.32");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing XSLoader method: " + e.getMessage());
        }
    }

    /**
     * Loads a PerlOnJava module.
     * <p>
     * If no module name is provided as an argument, uses caller() to determine
     * the calling package name, matching standard Perl XSLoader behavior.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing true on success, false on failure.
     */
    public static RuntimeList load(RuntimeArray args, int ctx) {
        String moduleName;

        if (args.isEmpty() || args.getFirst().toString().isEmpty()) {
            // No module name provided - use caller() to get the calling package
            RuntimeList callerInfo = RuntimeCode.caller(new RuntimeList(), SCALAR);
            if (callerInfo.isEmpty()) {
                return WarnDie.die(
                        new RuntimeScalar("Can't determine module name for XSLoader::load"),
                        new RuntimeScalar("\n")
                ).getList();
            }
            moduleName = callerInfo.scalar().toString();
            if (moduleName.isEmpty()) {
                return WarnDie.die(
                        new RuntimeScalar("Can't determine module name for XSLoader::load"),
                        new RuntimeScalar("\n")
                ).getList();
            }
        } else {
            moduleName = args.getFirst().toString();
        }

        // Convert Perl::Module::Name to org.perlonjava.runtime.perlmodule.PerlModuleName
        String[] parts = moduleName.split("::");
        StringBuilder className1 = new StringBuilder("org.perlonjava.runtime.perlmodule.");
        for (String part : parts) {
            className1.append(part);
        }
        String className = className1.toString();

        try {
            Class<?> clazz = Class.forName(className);
            Method initialize = clazz.getMethod("initialize");
            initialize.invoke(null);
            
            // Check version if provided
            if (args.size() > 1) {
                String requestedVersion = args.get(1).toString();
                if (!requestedVersion.isEmpty()) {
                    checkVersion(clazz, moduleName, requestedVersion);
                }
            }
            
            return scalarTrue.getList();
        } catch (Exception e) {
            // No Java XS class found. If the module's @ISA already has a
            // functional pure-Perl parent (set by the .pm file before calling
            // XSLoader::load), the module can function through inheritance.
            // Return success so the require doesn't fail.
            //
            // We skip non-functional base classes (Exporter, DynaLoader, etc.)
            // because their presence in @ISA does NOT mean the module can work
            // without its XS code — e.g. Clone has @ISA=(Exporter) but still
            // needs its own pure-Perl fallback to define clone().
            String isaKey = moduleName + "::ISA";
            RuntimeArray isa = GlobalVariable.getGlobalArray(isaKey);
            if (isa != null && hasFunctionalParent(isa)) {
                // @ISA fallback succeeded. Also try to load any jar: PERL5LIB shim
                // for this module, which may provide method overrides (e.g., bug fixes
                // for the pure-Perl parent that the XS version would normally handle).
                loadJarShimOverrides(moduleName);
                return scalarTrue.getList();
            }
            // Error message matches pattern /object version|loadable object/ that many
            // CPAN modules (DateTime, JSON::XS, etc.) expect for pure Perl fallback
            return WarnDie.die(
                    new RuntimeScalar("Can't load loadable object for module " + moduleName + 
                                      ": no Java XS implementation available"),
                    new RuntimeScalar("\n")
            ).getList();
        }
    }

    /**
     * Checks if the Java XS implementation version is compatible with the requested version.
     * If versions differ significantly, emits a warning but continues loading.
     * 
     * @param clazz The loaded Java XS class
     * @param moduleName The Perl module name
     * @param requestedVersion The version requested by XSLoader::load()
     */
    private static void checkVersion(Class<?> clazz, String moduleName, String requestedVersion) {
        try {
            Field versionField = clazz.getField("XS_VERSION");
            String javaVersion = (String) versionField.get(null);
            
            if (javaVersion != null && !versionsCompatible(javaVersion, requestedVersion)) {
                System.err.println("Warning: " + moduleName + " Java XS version " + javaVersion + 
                                   " may not be compatible with requested version " + requestedVersion);
            }
        } catch (NoSuchFieldException e) {
            // No XS_VERSION field - that's OK, just skip version check
        } catch (Exception e) {
            // Any other error - skip version check silently
        }
    }

    /**
     * Checks if two version strings are compatible.
     * Considers versions compatible if they have the same major version.
     * 
     * @param javaVersion The version from the Java XS implementation
     * @param requestedVersion The version requested by the Perl module
     * @return true if versions are likely compatible
     */
    private static boolean versionsCompatible(String javaVersion, String requestedVersion) {
        // Remove underscores (dev versions like 1.65_01)
        javaVersion = javaVersion.replace("_", "");
        requestedVersion = requestedVersion.replace("_", "");
        
        // Extract major version (part before first dot)
        String javaMajor = javaVersion.contains(".") ? 
                           javaVersion.substring(0, javaVersion.indexOf('.')) : javaVersion;
        String requestedMajor = requestedVersion.contains(".") ? 
                                requestedVersion.substring(0, requestedVersion.indexOf('.')) : requestedVersion;
        
        // Same major version is considered compatible
        return javaMajor.equals(requestedMajor);
    }

    /**
     * Checks whether @ISA contains at least one functional parent class.
     * Non-functional base classes (Exporter, DynaLoader, etc.) are skipped
     * because they provide infrastructure, not the module's actual methods.
     *
     * @param isa The module's @ISA array
     * @return true if at least one entry is a functional parent
     */
    private static boolean hasFunctionalParent(RuntimeArray isa) {
        for (int i = 0; i < isa.size(); i++) {
            String parent = isa.get(i).toString();
            if (!NON_FUNCTIONAL_ISA.contains(parent)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Stub implementation of bootstrap_inherit for compatibility.
     * In standard Perl, this is used for inheritance-aware XS loading.
     * In PerlOnJava, we just delegate to load().
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return A RuntimeList containing true on success, false on failure.
     */
    public static RuntimeList bootstrap_inherit(RuntimeArray args, int ctx) {
        return load(args, ctx);
    }

    /**
     * Tries to load method overrides from a jar: PERL5LIB shim for the given module.
     * 
     * When XSLoader::load falls back to @ISA inheritance (because no Java XS class exists),
     * the CPAN-installed .pm file is already loaded. However, our jar: PERL5LIB may contain
     * a shim with bug fixes or method overrides that should be applied on top.
     * 
     * This method reads the jar: version of the module (if it exists) and evals it,
     * which installs any subroutine definitions into the already-loaded package namespace.
     *
     * @param moduleName The fully qualified Perl module name (e.g., "Template::Stash::XS")
     */
    private static void loadJarShimOverrides(String moduleName) {
        // Guard against recursion: the shim code may call XSLoader::load() again
        // for the same module (e.g. Clone.pm's eval { XSLoader::load('Clone') })
        if (!shimLoadingInProgress.add(moduleName)) {
            return; // Already loading this module's shim — break the cycle
        }
        try {
            // Convert module name to file path: Template::Stash::XS -> Template/Stash/XS.pm
            String filePath = moduleName.replace("::", "/") + ".pm";
            String jarPath = GlobalContext.JAR_PERLLIB + "/" + filePath;
            
            // Check if a jar: version exists
            InputStream is = Jar.openInputStream(jarPath);
            if (is == null) {
                return; // No jar: shim for this module
            }
            
            // Read the content
            String code;
            try {
                code = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } finally {
                is.close();
            }
            
            // Eval the code to install any method overrides into the package
            EvalStringHandler.evalString(code, new RuntimeBase[0], jarPath, 1);
        } catch (Exception e) {
            // Silently ignore - the module works via inheritance anyway
        } finally {
            shimLoadingInProgress.remove(moduleName);
        }
    }
}
