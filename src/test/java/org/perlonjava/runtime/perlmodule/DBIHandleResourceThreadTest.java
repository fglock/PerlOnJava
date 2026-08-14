package org.perlonjava.runtime.perlmodule;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.runtime.runtimetypes.PerlRuntime;
import org.perlonjava.runtime.runtimetypes.RuntimeGraphCloner;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class DBIHandleResourceThreadTest {
    @Test
    void inheritedResourceIsInvalidAndParentResourceRemainsUsable() {
        PerlRuntime parent = new PerlRuntime();
        PerlRuntime child = new PerlRuntime();
        RuntimeScalar parentSlot;

        try (PerlRuntime.Binding ignored = parent.bind()) {
            parentSlot = new RuntimeScalar(DBIHandleResource.owned("parent-connection"));
        }
        RuntimeScalar childSlot = new RuntimeGraphCloner(parent, child).cloneGraph(parentSlot);

        try (PerlRuntime.Binding ignored = child.bind()) {
            DBIThreadOwnershipException error = assertThrows(DBIThreadOwnershipException.class,
                    () -> resource(childSlot).requireCurrentOwner(
                            "DBD::SQLite::db", "ping", String.class));
            assertTrue(error.getMessage().contains("owned by thread"));
            assertTrue(error.getMessage().contains("not current thread"));
        }
        try (PerlRuntime.Binding ignored = parent.bind()) {
            assertEquals("parent-connection", resource(parentSlot).requireCurrentOwner(
                    "DBD::SQLite::db", "ping", String.class));
        }
    }

    @Test
    void resourceReturnedThroughJoinStyleCloneNeverReactivates() {
        PerlRuntime parent = new PerlRuntime();
        PerlRuntime child = new PerlRuntime();
        RuntimeScalar parentSlot;

        try (PerlRuntime.Binding ignored = parent.bind()) {
            parentSlot = new RuntimeScalar(DBIHandleResource.owned("parent-connection"));
        }
        RuntimeScalar inherited = new RuntimeGraphCloner(parent, child).cloneGraph(parentSlot);
        RuntimeScalar returned = new RuntimeGraphCloner(child, parent).cloneGraph(inherited);

        try (PerlRuntime.Binding ignored = parent.bind()) {
            assertThrows(DBIThreadOwnershipException.class,
                    () -> resource(returned).requireCurrentOwner(
                            "DBD::SQLite::db", "ping", String.class));
        }
    }

    @Test
    void childCreatedResourceIsInvalidWhenReturnedToParent() {
        PerlRuntime parent = new PerlRuntime();
        PerlRuntime child = new PerlRuntime();
        RuntimeScalar childSlot;

        try (PerlRuntime.Binding ignored = child.bind()) {
            childSlot = new RuntimeScalar(DBIHandleResource.owned("child-connection"));
            assertEquals("child-connection", resource(childSlot).requireCurrentOwner(
                    "DBD::SQLite::db", "ping", String.class));
        }
        RuntimeScalar returned = new RuntimeGraphCloner(child, parent).cloneGraph(childSlot);
        try (PerlRuntime.Binding ignored = parent.bind()) {
            assertThrows(DBIThreadOwnershipException.class,
                    () -> resource(returned).requireCurrentOwner(
                            "DBD::SQLite::db", "ping", String.class));
        }
    }

    @SuppressWarnings("unchecked")
    private static DBIHandleResource<String> resource(RuntimeScalar scalar) {
        return (DBIHandleResource<String>) scalar.value;
    }
}
