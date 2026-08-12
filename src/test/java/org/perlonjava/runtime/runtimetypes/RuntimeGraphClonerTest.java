package org.perlonjava.runtime.runtimetypes;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class RuntimeGraphClonerTest {

    @Test
    void preservesCyclesAliasesBlessingsWeaknessAndSourceGraph() {
        PerlRuntime sourceRuntime = new PerlRuntime();
        PerlRuntime targetRuntime = new PerlRuntime();
        RuntimeArray sourceArray = new RuntimeArray();
        RuntimeScalar first = sourceArray.createAnonymousReference();
        RuntimeScalar alias = new RuntimeScalar();
        alias.type = first.type;
        alias.value = first.value;
        sourceArray.elements.add(first);
        sourceArray.elements.add(alias);

        try (PerlRuntime.Binding ignored = sourceRuntime.bind()) {
            sourceArray.blessId = NameNormalizer.getBlessId("Graph::Node");
            WeakRefRegistry.weaken(alias);
        }

        RuntimeArray cloned = (RuntimeArray) new RuntimeGraphCloner(sourceRuntime, targetRuntime)
                .cloneGraph(sourceArray);

        assertNotSame(sourceArray, cloned);
        assertSame(cloned, cloned.elements.get(0).value);
        assertSame(cloned, cloned.elements.get(1).value);
        assertNotSame(first, cloned.elements.get(0));
        try (PerlRuntime.Binding ignored = targetRuntime.bind()) {
            assertEquals("Graph::Node", NameNormalizer.getBlessStr(cloned.blessId));
            assertFalse(WeakRefRegistry.isweak(cloned.elements.get(0)));
            assertTrue(WeakRefRegistry.isweak(cloned.elements.get(1)));
        }

        assertSame(sourceArray, first.value);
        assertSame(sourceArray, alias.value);
        try (PerlRuntime.Binding ignored = sourceRuntime.bind()) {
            assertTrue(WeakRefRegistry.isweak(alias));
        }
    }

    @Test
    void sharesImmutableConstantsAndDropsNonPortableIoResources() {
        PerlRuntime sourceRuntime = new PerlRuntime();
        PerlRuntime targetRuntime = new PerlRuntime();
        RuntimeScalarReadOnly constant = new RuntimeScalarReadOnly("constant");
        RuntimeScalar resource = new RuntimeScalar(new RuntimeIO());

        RuntimeGraphCloner cloner = new RuntimeGraphCloner(sourceRuntime, targetRuntime);
        List<RuntimeBase> cloned = cloner.cloneRoots(List.of(constant, resource));

        assertSame(constant, cloned.get(0));
        RuntimeScalar clonedResource = (RuntimeScalar) cloned.get(1);
        assertEquals(RuntimeScalarType.UNDEF, clonedResource.type);
        assertNull(clonedResource.value);
        assertTrue(resource.defined().getBoolean());
    }

    @Test
    void preservesAliasingAcrossSeparateRoots() {
        PerlRuntime sourceRuntime = new PerlRuntime();
        PerlRuntime targetRuntime = new PerlRuntime();
        RuntimeHash referent = new RuntimeHash();
        referent.elements.put("value", new RuntimeScalar(42));
        RuntimeScalar one = referent.createAnonymousReference();
        RuntimeScalar two = new RuntimeScalar();
        two.type = one.type;
        two.value = referent;

        List<RuntimeBase> cloned = new RuntimeGraphCloner(sourceRuntime, targetRuntime)
                .cloneRoots(List.of(one, two));

        assertSame(((RuntimeScalar) cloned.get(0)).value,
                ((RuntimeScalar) cloned.get(1)).value);
        assertNotSame(referent, ((RuntimeScalar) cloned.get(0)).value);
        assertEquals(42, ((RuntimeHash) ((RuntimeScalar) cloned.get(0)).value)
                .elements.get("value").getInt());
    }
}
