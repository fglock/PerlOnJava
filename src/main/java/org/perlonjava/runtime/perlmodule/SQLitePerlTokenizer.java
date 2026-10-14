package org.perlonjava.runtime.perlmodule;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeContextType;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

/**
 * Registers DBD::SQLite's FTS3/4 {@code tokenize=perl} contract on an Xerial
 * SQLite connection. The native SQLite callbacks are FFM upcalls so each
 * tokenizer factory and iterator still executes as ordinary Perl code.
 */
final class SQLitePerlTokenizer implements AutoCloseable {
    private static final int SQLITE_OK = 0;
    private static final int SQLITE_ERROR = 1;
    private static final int SQLITE_DONE = 101;
    private static final long MAX_NATIVE_STRING_BYTES = 1L << 30;
    private static final ValueLayout.OfInt C_INT = ValueLayout.JAVA_INT;
    private static final AddressLayout C_POINTER = ValueLayout.ADDRESS;
    private static final Linker LINKER = Linker.nativeLinker();
    private static final MemoryLayout MODULE_LAYOUT = MemoryLayout.structLayout(
            C_INT.withName("iVersion"),
            MemoryLayout.paddingLayout(C_POINTER.byteAlignment() - C_INT.byteSize()),
            C_POINTER.withName("xCreate"),
            C_POINTER.withName("xDestroy"),
            C_POINTER.withName("xOpen"),
            C_POINTER.withName("xClose"),
            C_POINTER.withName("xNext"));
    private static final Map<Connection, SQLitePerlTokenizer> INSTALLED = new ConcurrentHashMap<>();
    private static volatile Path extensionPath;

    private final Arena arena = Arena.ofShared();
    private final Map<Long, TokenizerState> tokenizers = new HashMap<>();
    private final Map<Long, CursorState> cursors = new HashMap<>();
    private final MemorySegment module;
    private final MemorySegment registrationBlob;

    static void install(Connection connection) throws SQLException {
        if (INSTALLED.containsKey(connection)) {
            return;
        }
        SQLitePerlTokenizer registry = new SQLitePerlTokenizer();
        try {
            registry.enableTokenizerRegistration(connection);
            registry.registerModule(connection);
            SQLitePerlTokenizer previous = INSTALLED.putIfAbsent(connection, registry);
            if (previous != null) {
                registry.close();
            }
        } catch (Throwable failure) {
            registry.close();
            if (failure instanceof SQLException sqlException) {
                throw sqlException;
            }
            throw new SQLException("Could not install the Perl FTS tokenizer: "
                    + failure.getMessage(), failure);
        }
    }

    static void remove(Connection connection) {
        SQLitePerlTokenizer registry = INSTALLED.remove(connection);
        if (registry != null) {
            registry.close();
        }
    }

    private SQLitePerlTokenizer() {
        try {
            module = arena.allocate(MODULE_LAYOUT);
            module.set(C_INT, MODULE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("iVersion")), 0);
            module.set(C_POINTER, offset("xCreate"), upcall("createTokenizer",
                    MethodType.methodType(int.class, int.class, MemorySegment.class, MemorySegment.class),
                    FunctionDescriptor.of(C_INT, C_INT, C_POINTER, C_POINTER)));
            module.set(C_POINTER, offset("xDestroy"), upcall("destroyTokenizer",
                    MethodType.methodType(int.class, MemorySegment.class),
                    FunctionDescriptor.of(C_INT, C_POINTER)));
            module.set(C_POINTER, offset("xOpen"), upcall("openCursor",
                    MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class,
                            int.class, MemorySegment.class),
                    FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_INT, C_POINTER)));
            module.set(C_POINTER, offset("xClose"), upcall("closeCursor",
                    MethodType.methodType(int.class, MemorySegment.class),
                    FunctionDescriptor.of(C_INT, C_POINTER)));
            module.set(C_POINTER, offset("xNext"), upcall("nextToken",
                    MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class,
                            MemorySegment.class, MemorySegment.class, MemorySegment.class, MemorySegment.class),
                    FunctionDescriptor.of(C_INT, C_POINTER, C_POINTER, C_POINTER, C_POINTER,
                            C_POINTER, C_POINTER)));
            registrationBlob = arena.allocate(C_POINTER.byteSize(), C_POINTER.byteAlignment());
            registrationBlob.set(C_POINTER, 0, module);
        } catch (Throwable failure) {
            arena.close();
            throw new IllegalStateException("Could not prepare FTS tokenizer callbacks", failure);
        }
    }

    private MemorySegment upcall(String methodName, MethodType type, FunctionDescriptor descriptor)
            throws ReflectiveOperationException {
        MethodHandle handle = MethodHandles.lookup().findVirtual(SQLitePerlTokenizer.class, methodName, type)
                .bindTo(this);
        return LINKER.upcallStub(handle, descriptor, arena);
    }

    private static long offset(String member) {
        return MODULE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement(member));
    }

    private void enableTokenizerRegistration(Connection connection) throws SQLException, IOException {
        if (!(connection instanceof org.sqlite.SQLiteConnection sqliteConnection)) {
            throw new SQLException("Perl FTS tokenizers require the Xerial SQLite JDBC connection");
        }
        sqliteConnection.getDatabase().enable_load_extension(true);
        try {
            Path path = helperExtensionPath();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT load_extension(?, 'sqlite3_perlonjava_tokenizer_enable_init')")) {
                statement.setString(1, path.toAbsolutePath().toString());
                statement.executeQuery().close();
            }
        } finally {
            sqliteConnection.getDatabase().enable_load_extension(false);
        }
    }

    private static Path helperExtensionPath() throws IOException {
        Path path = extensionPath;
        if (path != null) return path;
        synchronized (SQLitePerlTokenizer.class) {
            path = extensionPath;
            if (path != null) return path;
            String resource = "/org/perlonjava/sqlite/" + platformLibraryName();
            try (var input = SQLitePerlTokenizer.class.getResourceAsStream(resource)) {
                if (input == null) {
                    throw new IOException("Missing bundled SQLite tokenizer helper: " + resource);
                }
                path = Files.createTempFile("perlonjava-sqlite-tokenizer-", librarySuffix());
                Files.copy(input, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                path.toFile().deleteOnExit();
                extensionPath = path;
                return path;
            }
        }
    }

    private void registerModule(Connection connection) throws SQLException {
        // Xerial defers FTS3's connection-local tokenizer registry until the
        // first FTS virtual table is created. Initialize that registry before
        // inserting the Perl module pointer into it.
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE VIRTUAL TABLE temp.__perlonjava_fts_init USING fts4(value)");
            statement.execute("DROP TABLE temp.__perlonjava_fts_init");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT fts3_tokenizer(?, ?)")) {
            statement.setString(1, "perl");
            statement.setBytes(2, pointerBytes(registrationBlob));
            statement.executeQuery().close();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT fts3_tokenizer('perl')");
             var result = statement.executeQuery()) {
            if (!result.next() || result.getBytes(1) == null
                    || !java.util.Arrays.equals(result.getBytes(1), pointerBytes(registrationBlob))) {
                throw new SQLException("SQLite did not retain the Perl tokenizer module pointer");
            }
        }
    }

    private static byte[] pointerBytes(MemorySegment pointerSlot) {
        return pointerSlot.asSlice(0, C_POINTER.byteSize()).toArray(ValueLayout.JAVA_BYTE);
    }

    private static String platformLibraryName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return "perlonjava_sqlite_tokenizer.dll";
        if (os.contains("mac") || os.contains("darwin")) return "libperlonjava_sqlite_tokenizer.dylib";
        return "libperlonjava_sqlite_tokenizer.so";
    }

    private static String librarySuffix() {
        String name = platformLibraryName();
        return name.substring(name.lastIndexOf('.'));
    }

    private int createTokenizer(int argc, MemorySegment argv, MemorySegment output) {
        try {
            if (argc < 1 || argv.address() == 0 || output.address() == 0) return SQLITE_ERROR;
            MemorySegment values = argv.reinterpret((long) argc * C_POINTER.byteSize());
            String function = cString(values.get(C_POINTER, 0));
            RuntimeScalar code = GlobalVariable.getGlobalCodeRef(function);
            RuntimeScalar factory = RuntimeCode.apply(code, new RuntimeArray(), RuntimeContextType.SCALAR).scalar();
            if (factory == null || !factory.getDefinedBoolean()) return SQLITE_ERROR;

            MemorySegment nativeTokenizer = arena.allocate(C_POINTER.byteSize(), C_POINTER.byteAlignment());
            tokenizers.put(nativeTokenizer.address(), new TokenizerState(factory));
            output.reinterpret(C_POINTER.byteSize()).set(C_POINTER, 0, nativeTokenizer);
            return SQLITE_OK;
        } catch (Throwable failure) {
            return SQLITE_ERROR;
        }
    }

    private int destroyTokenizer(MemorySegment nativeTokenizer) {
        try {
            tokenizers.remove(nativeTokenizer.address());
            return SQLITE_OK;
        } catch (Throwable failure) {
            return SQLITE_ERROR;
        }
    }

    private int openCursor(MemorySegment nativeTokenizer, MemorySegment input, int byteLength,
            MemorySegment output) {
        try {
            TokenizerState tokenizer = tokenizers.get(nativeTokenizer.address());
            if (tokenizer == null || output.address() == 0) return SQLITE_ERROR;
            String text = readInput(input, byteLength);
            RuntimeArray args = new RuntimeArray();
            RuntimeArray.push(args, new RuntimeScalar(text));
            RuntimeScalar iterator = RuntimeCode.apply(
                    tokenizer.factory, args, RuntimeContextType.SCALAR).scalar();
            if (iterator == null || !iterator.getDefinedBoolean()) return SQLITE_ERROR;

            MemorySegment nativeCursor = arena.allocate(C_POINTER.byteSize(), C_POINTER.byteAlignment());
            cursors.put(nativeCursor.address(), new CursorState(iterator, text));
            output.reinterpret(C_POINTER.byteSize()).set(C_POINTER, 0, nativeCursor);
            return SQLITE_OK;
        } catch (Throwable failure) {
            return SQLITE_ERROR;
        }
    }

    private int closeCursor(MemorySegment nativeCursor) {
        try {
            CursorState cursor = cursors.remove(nativeCursor.address());
            if (cursor != null && cursor.tokenArena != null) cursor.tokenArena.close();
            return SQLITE_OK;
        } catch (Throwable failure) {
            return SQLITE_ERROR;
        }
    }

    private int nextToken(MemorySegment nativeCursor, MemorySegment outputToken,
            MemorySegment outputLength, MemorySegment outputStart, MemorySegment outputEnd,
            MemorySegment outputPosition) {
        try {
            CursorState cursor = cursors.get(nativeCursor.address());
            if (cursor == null) return SQLITE_ERROR;
            RuntimeList values = RuntimeCode.apply(
                    cursor.iterator, new RuntimeArray(), RuntimeContextType.LIST);
            if (values.elements.isEmpty()) return SQLITE_DONE;
            if (values.elements.size() != 5) {
                throw new IllegalStateException("Perl FTS tokenizer iterator must return five values");
            }
            Iterator<RuntimeScalar> iterator = values.iterator();
            String token = iterator.next().toString();
            iterator.next(); // Token length is recomputed after UTF-8 encoding.
            int start = iterator.next().getInt();
            int end = iterator.next().getInt();
            int position = iterator.next().getInt();

            byte[] encoded = token.getBytes(StandardCharsets.UTF_8);
            int byteStart = utf8ByteOffset(cursor.text, start);
            int byteEnd = utf8ByteOffset(cursor.text, end);
            if (cursor.tokenArena != null) cursor.tokenArena.close();
            cursor.tokenArena = Arena.ofShared();
            MemorySegment tokenMemory = cursor.tokenArena.allocate(Math.max(1, encoded.length), 1);
            tokenMemory.asByteBuffer().put(encoded);
            outputToken.reinterpret(C_POINTER.byteSize()).set(C_POINTER, 0, tokenMemory);
            outputLength.reinterpret(C_INT.byteSize()).set(C_INT, 0, encoded.length);
            outputStart.reinterpret(C_INT.byteSize()).set(C_INT, 0, byteStart);
            outputEnd.reinterpret(C_INT.byteSize()).set(C_INT, 0, byteEnd);
            outputPosition.reinterpret(C_INT.byteSize()).set(C_INT, 0, position);
            return SQLITE_OK;
        } catch (Throwable failure) {
            return SQLITE_ERROR;
        }
    }

    private static int utf8ByteOffset(String text, int codePointOffset) {
        if (codePointOffset < 0 || codePointOffset > text.codePointCount(0, text.length())) {
            throw new IllegalArgumentException("Perl tokenizer returned an offset outside its input");
        }
        int utf16Offset = text.offsetByCodePoints(0, codePointOffset);
        return text.substring(0, utf16Offset).getBytes(StandardCharsets.UTF_8).length;
    }

    private static String readInput(MemorySegment input, int byteLength) {
        if (input.address() == 0) return "";
        if (byteLength >= 0) {
            byte[] bytes = input.reinterpret(byteLength).toArray(ValueLayout.JAVA_BYTE);
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return input.reinterpret(MAX_NATIVE_STRING_BYTES).getString(0);
    }

    private static String cString(MemorySegment pointer) {
        if (pointer.address() == 0) return "";
        return pointer.reinterpret(MAX_NATIVE_STRING_BYTES).getString(0);
    }

    @Override
    public void close() {
        for (CursorState cursor : new ArrayList<>(cursors.values())) {
            if (cursor.tokenArena != null) cursor.tokenArena.close();
        }
        cursors.clear();
        tokenizers.clear();
        arena.close();
    }

    private record TokenizerState(RuntimeScalar factory) {}

    private static final class CursorState {
        private final RuntimeScalar iterator;
        private final String text;
        private Arena tokenArena;

        private CursorState(RuntimeScalar iterator, String text) {
            this.iterator = iterator;
            this.text = text;
        }
    }
}
