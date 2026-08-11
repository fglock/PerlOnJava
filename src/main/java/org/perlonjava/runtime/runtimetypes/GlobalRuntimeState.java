package org.perlonjava.runtime.runtimetypes;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Runtime-owned Perl global state.
 *
 * <p>Phases 8a through 8c own scalar/array/hash values, CODE-slot state, and
 * named IO/format slots. Globs, stashes, aliases, declarations, and package
 * services move in later Phase 8 changes.</p>
 */
public final class GlobalRuntimeState {
    private final Map<String, RuntimeScalar> scalarValues = new HashMap<>();
    private final Map<String, RuntimeArray> arrayValues = new HashMap<>();
    private final Map<String, RuntimeHash> hashValues = new HashMap<>();
    private final Map<String, RuntimeScalar> foreachScalarAliases = new HashMap<>();
    private final Map<String, Boolean> importedSubs = new HashMap<>();
    private final Map<String, Boolean> operatorOverrideGlobs = new HashMap<>();
    private final Map<String, RuntimeScalar> codeRefs = new HashMap<>();
    private final Map<String, RuntimeScalar> pseudoConstants = new HashMap<>();
    private final Map<String, RuntimeScalar> pinnedCodeRefs = new HashMap<>();
    private final Set<String> deletedCodeRefPins = new HashSet<>();
    private final Map<Integer, RuntimeScalar> compiledCodeRefs = new HashMap<>();
    private final Map<String, Integer> localizedCodeRefDepth = new HashMap<>();
    private final IdentityHashMap<RuntimeScalar, String> displacedLocalizedCodeRefs =
            new IdentityHashMap<>();
    private final Map<String, RuntimeGlob> ioSlots = new HashMap<>();
    private final Map<String, RuntimeFormat> formatSlots = new HashMap<>();
    private final Set<String> hiddenIoSlotsAfterStashDelete = new HashSet<>();
    private int nextCompiledCodeRefId = 1;
    private long stashEnumerationVersion;
    private boolean coreGlobalsInitialized;

    /** Core package scalar slots owned by this runtime. */
    public Map<String, RuntimeScalar> scalarValues() {
        return scalarValues;
    }

    /** Core package array slots owned by this runtime. */
    public Map<String, RuntimeArray> arrayValues() {
        return arrayValues;
    }

    /** Core package hash slots owned by this runtime. */
    public Map<String, RuntimeHash> hashValues() {
        return hashValues;
    }

    Map<String, RuntimeScalar> foreachScalarAliases() {
        return foreachScalarAliases;
    }

    public Map<String, RuntimeScalar> codeRefs() {
        return codeRefs;
    }

    Map<String, Boolean> importedSubs() {
        return importedSubs;
    }

    Map<String, Boolean> operatorOverrideGlobs() {
        return operatorOverrideGlobs;
    }

    Map<String, RuntimeScalar> pseudoConstants() {
        return pseudoConstants;
    }

    Map<String, RuntimeScalar> pinnedCodeRefs() {
        return pinnedCodeRefs;
    }

    Set<String> deletedCodeRefPins() {
        return deletedCodeRefPins;
    }

    Map<Integer, RuntimeScalar> compiledCodeRefs() {
        return compiledCodeRefs;
    }

    Map<String, Integer> localizedCodeRefDepth() {
        return localizedCodeRefDepth;
    }

    IdentityHashMap<RuntimeScalar, String> displacedLocalizedCodeRefs() {
        return displacedLocalizedCodeRefs;
    }

    Map<String, RuntimeGlob> ioSlots() {
        return ioSlots;
    }

    Map<String, RuntimeFormat> formatSlots() {
        return formatSlots;
    }

    Set<String> hiddenIoSlotsAfterStashDelete() {
        return hiddenIoSlotsAfterStashDelete;
    }

    synchronized int registerCompiledCodeRef(RuntimeScalar ref) {
        int id = nextCompiledCodeRefId++;
        compiledCodeRefs.put(id, ref);
        return id;
    }

    long stashEnumerationVersion() {
        return stashEnumerationVersion;
    }

    void invalidateStashEnumeration() {
        stashEnumerationVersion++;
    }

    /** Return whether this runtime already installed its core globals. */
    public boolean coreGlobalsInitialized() {
        return coreGlobalsInitialized;
    }

    /** Record that this runtime installed its core globals. */
    public void markCoreGlobalsInitialized() {
        coreGlobalsInitialized = true;
    }

    /** Make the next top-level compilation initialize this runtime again. */
    public void resetCoreGlobalsInitialization() {
        coreGlobalsInitialized = false;
    }

    void clearCoreValues() {
        scalarValues.clear();
        arrayValues.clear();
        hashValues.clear();
        foreachScalarAliases.clear();
        coreGlobalsInitialized = false;
        invalidateStashEnumeration();
    }

    void clearCodeValues() {
        importedSubs.clear();
        operatorOverrideGlobs.clear();
        codeRefs.clear();
        pseudoConstants.clear();
        pinnedCodeRefs.clear();
        deletedCodeRefPins.clear();
        compiledCodeRefs.clear();
        localizedCodeRefDepth.clear();
        displacedLocalizedCodeRefs.clear();
        nextCompiledCodeRefId = 1;
        invalidateStashEnumeration();
    }

    void clearIoAndFormatValues() {
        ioSlots.clear();
        formatSlots.clear();
        hiddenIoSlotsAfterStashDelete.clear();
        invalidateStashEnumeration();
    }
}
