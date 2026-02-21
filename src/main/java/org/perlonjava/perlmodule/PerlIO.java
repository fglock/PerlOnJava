package org.perlonjava.perlmodule;

import org.perlonjava.io.IOLayer;
import org.perlonjava.io.LayeredIOHandle;
import org.perlonjava.runtime.runtimetypes.*;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarTrue;

/**
 * The Re class provides functionalities similar to the Perl re module.
 */
public class PerlIO extends PerlModuleBase {

    /**
     * Constructor initializes the module.
     */
    public PerlIO() {
        super("PerlIO", true);
    }

    /**
     * Static initializer to set up the module.
     */
    public static void initialize() {
        PerlIO perlio = new PerlIO();
        try {
            perlio.registerMethod("PerlIO::Layer::find", "find", "$");
            perlio.registerMethod("get_layers", "$;@");
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing PerlIO method: " + e.getMessage());
        }
    }

    /**
     * Placeholder method to PerlIO::Layer->find.
     *
     * @param args The arguments passed to the method.
     * @param ctx  The context in which the method is called.
     * @return Empty list
     */
    public static RuntimeList find(RuntimeArray args, int ctx) {
        return scalarTrue.getList();
    }

    // PerlIO::get_layers($fh, @options) implementation
    // Return the currently applied layers as a string
    // Optional arguments like 'output', 'details' are accepted but currently ignored
    public static RuntimeList get_layers(RuntimeArray args, int ctx) {
        RuntimeIO fh = args.get(0).getRuntimeIO();
        if (fh instanceof TieHandle) {
            throw new PerlCompilerException("can't get_layers on tied handle");
        }
        
        // Parse optional arguments (output => 1, details => 1, etc.)
        // For now, we ignore these options and just return layer names
        
        RuntimeArray layers = new RuntimeArray();
        if (fh.ioHandle instanceof LayeredIOHandle layeredIOHandle) {
            for (IOLayer layer : layeredIOHandle.activeLayers) {
                RuntimeArray.push(layers, new RuntimeScalar(layer.getLayerName()));
            }
        }
        return layers.getList();
    }
}
