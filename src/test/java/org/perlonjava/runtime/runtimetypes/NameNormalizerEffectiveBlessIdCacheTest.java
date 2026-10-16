package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class NameNormalizerEffectiveBlessIdCacheTest {
    @Test
    void invalidationRefreshesEffectiveBlessIdForExistingObjects() {
        PerlRuntime runtime = new PerlRuntime().initialize();
        try (PerlRuntime.Binding ignored = runtime.bind()) {
            String className = "EffectiveBlessIdCache::Object";
            int originalId = NameNormalizer.getBlessId(className);
            assertEquals(originalId, NameNormalizer.getEffectiveBlessId(originalId));

            NameNormalizer.invalidateBlessIdCache();
            runtime.nameNormalizerState.blessIdCache.put(className, -1);

            assertEquals(-1, NameNormalizer.getEffectiveBlessId(originalId));
        }
    }
}
