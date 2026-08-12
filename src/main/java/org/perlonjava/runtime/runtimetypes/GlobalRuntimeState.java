package org.perlonjava.runtime.runtimetypes;

import org.perlonjava.backend.jvm.CustomClassLoader;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runtime-owned Perl global state.
 *
 * <p>Phases 8a through 8e own scalar/array/hash values, CODE-slot state,
 * named IO/format slots, glob/stash identity, declarations, and package
 * services.</p>
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
    private final Map<String, String> stashAliases = new HashMap<>();
    private final Map<String, String> resolvedStashAliases = new HashMap<>();
    private final Map<String, String> globAliases = new HashMap<>();
    private final Map<String, List<HashSpecialVariable.StashEntryName>> stashEntryCache =
            new HashMap<>();
    private final Map<String, Boolean> packageExistsCache = new HashMap<>();
    private final Set<String> declaredGlobalVariables = new HashSet<>();
    private final Set<String> declaredGlobalArrays = new HashSet<>();
    private final Set<String> declaredGlobalHashes = new HashSet<>();
    private final Set<String> classNames = new HashSet<>();
    private final Map<String, Set<String>> classFields = new HashMap<>();
    private final Map<String, String> classParents = new HashMap<>();
    private final Map<String, String> packageVersions = new HashMap<>();
    private CustomClassLoader generatedClassLoader =
            new CustomClassLoader(GlobalVariable.class.getClassLoader());
    private int nextCompiledCodeRefId = 1;
    private long stashEnumerationVersion;
    private long cachedStashEnumerationVersion = -1;
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

    Map<String, String> stashAliases() {
        return stashAliases;
    }

    Map<String, String> resolvedStashAliases() {
        return resolvedStashAliases;
    }

    Map<String, String> globAliases() {
        return globAliases;
    }

    Map<String, List<HashSpecialVariable.StashEntryName>> stashEntryCache() {
        return stashEntryCache;
    }

    Map<String, Boolean> packageExistsCache() {
        return packageExistsCache;
    }

    Set<String> declaredGlobalVariables() {
        return declaredGlobalVariables;
    }

    Set<String> declaredGlobalArrays() {
        return declaredGlobalArrays;
    }

    Set<String> declaredGlobalHashes() {
        return declaredGlobalHashes;
    }

    /** Perl 5.38+ class declarations owned by this runtime. */
    public Set<String> classNames() {
        return classNames;
    }

    /** Field declarations keyed by their owning Perl class. */
    public Map<String, Set<String>> classFields() {
        return classFields;
    }

    /** Parent declarations used by the class-field parser. */
    public Map<String, String> classParents() {
        return classParents;
    }

    /** Package versions visible to later compilation units in this runtime. */
    public Map<String, String> packageVersions() {
        return packageVersions;
    }

    CustomClassLoader generatedClassLoader() {
        return generatedClassLoader;
    }

    void generatedClassLoader(CustomClassLoader loader) {
        generatedClassLoader = loader;
    }

    long cachedStashEnumerationVersion() {
        return cachedStashEnumerationVersion;
    }

    void cachedStashEnumerationVersion(long version) {
        cachedStashEnumerationVersion = version;
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

    void clearGlobAndStashValues() {
        stashAliases.clear();
        resolvedStashAliases.clear();
        globAliases.clear();
        stashEntryCache.clear();
        cachedStashEnumerationVersion = -1;
        invalidateStashEnumeration();
    }

    void clearDeclarationsAndPackageServices() {
        packageExistsCache.clear();
        declaredGlobalVariables.clear();
        declaredGlobalArrays.clear();
        declaredGlobalHashes.clear();
        classNames.clear();
        classFields.clear();
        classParents.clear();
        packageVersions.clear();
        generatedClassLoader = new CustomClassLoader(GlobalVariable.class.getClassLoader());
    }

    /** Copy package-owned interpreter state through one ithread graph cloner. */
    void snapshotInto(GlobalRuntimeState target, RuntimeGraphCloner cloner) {
        cloneMap(scalarValues, target.scalarValues, cloner, RuntimeScalar.class);
        cloneMap(arrayValues, target.arrayValues, cloner, RuntimeArray.class);
        cloneMap(hashValues, target.hashValues, cloner, RuntimeHash.class);
        cloneMap(foreachScalarAliases, target.foreachScalarAliases, cloner, RuntimeScalar.class);
        cloneMap(codeRefs, target.codeRefs, cloner, RuntimeScalar.class);
        cloneMap(pseudoConstants, target.pseudoConstants, cloner, RuntimeScalar.class);
        cloneMap(pinnedCodeRefs, target.pinnedCodeRefs, cloner, RuntimeScalar.class);
        for (Map.Entry<Integer, RuntimeScalar> entry : compiledCodeRefs.entrySet()) {
            target.compiledCodeRefs.put(entry.getKey(),
                    (RuntimeScalar) cloner.cloneValue(entry.getValue()));
        }

        target.importedSubs.putAll(importedSubs);
        target.operatorOverrideGlobs.putAll(operatorOverrideGlobs);
        target.deletedCodeRefPins.addAll(deletedCodeRefPins);
        target.localizedCodeRefDepth.putAll(localizedCodeRefDepth);
        target.stashAliases.putAll(stashAliases);
        target.resolvedStashAliases.putAll(resolvedStashAliases);
        target.globAliases.putAll(globAliases);
        target.packageExistsCache.putAll(packageExistsCache);
        target.declaredGlobalVariables.addAll(declaredGlobalVariables);
        target.declaredGlobalArrays.addAll(declaredGlobalArrays);
        target.declaredGlobalHashes.addAll(declaredGlobalHashes);
        target.classNames.addAll(classNames);
        classFields.forEach((name, fields) -> target.classFields.put(name, new HashSet<>(fields)));
        target.classParents.putAll(classParents);
        target.packageVersions.putAll(packageVersions);
        target.nextCompiledCodeRefId = nextCompiledCodeRefId;
        target.stashEnumerationVersion = stashEnumerationVersion;
        target.coreGlobalsInitialized = coreGlobalsInitialized;
        // Class loaders, caches, named IO and formats are child-owned/fresh.
    }

    private static <T extends RuntimeBase> void cloneMap(
            Map<String, T> source, Map<String, T> target,
            RuntimeGraphCloner cloner, Class<T> type) {
        for (Map.Entry<String, T> entry : source.entrySet()) {
            target.put(entry.getKey(), type.cast(cloner.cloneValue(entry.getValue())));
        }
    }
}
