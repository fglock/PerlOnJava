package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.runtimetypes.*;

import java.nio.charset.StandardCharsets;
import java.sql.*;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarTrue;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;

/**
 * Portable DBM backend used by Perl's dbmopen/dbmclose operators.
 *
 * <p>The first PerlOnJava DBM format is an SQLite file containing byte-string
 * keys and values.  It intentionally does not claim compatibility with native
 * Berkeley DB, SDBM, NDBM, or GDBM files.</p>
 */
public final class DBM extends PerlModuleBase {
    private static final String CONNECTION = "__perlonjava_dbm_connection";
    private static final String READ_ONLY = "__perlonjava_dbm_read_only";
    private static final String KEYS = "__perlonjava_dbm_keys";
    private static final String KEY_INDEX = "__perlonjava_dbm_key_index";

    private DBM() {
        super("PerlOnJava::DBM", false);
    }

    public static void initialize() {
        DBM module = new DBM();
        try {
            module.registerMethod("TIEHASH", null);
            module.registerMethod("FETCH", null);
            module.registerMethod("STORE", null);
            module.registerMethod("DELETE", null);
            module.registerMethod("CLEAR", null);
            module.registerMethod("EXISTS", null);
            module.registerMethod("FIRSTKEY", null);
            module.registerMethod("NEXTKEY", null);
            module.registerMethod("SCALAR", null);
            module.registerMethod("UNTIE", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Cannot register PerlOnJava::DBM", e);
        }
    }

    private static RuntimeHash self(RuntimeArray args) {
        return args.get(0).hashDeref();
    }

    private static Connection connection(RuntimeHash self) {
        RuntimeScalar value = self.get(CONNECTION);
        if (value == null || !(value.value instanceof Connection connection)) {
            throw new PerlCompilerException("DBM handle is closed");
        }
        return connection;
    }

    private static boolean readOnly(RuntimeHash self) {
        return self.get(READ_ONLY).getBoolean();
    }

    private static byte[] bytes(RuntimeScalar value) {
        if (value.type == RuntimeScalarType.BYTE_STRING) {
            return value.toString().getBytes(StandardCharsets.ISO_8859_1);
        }
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static RuntimeScalar scalar(byte[] value) {
        return value == null ? scalarUndef : new RuntimeScalar(value);
    }

    private static PerlCompilerException failure(String operation, Exception error) {
        return new PerlCompilerException("DBM " + operation + " failed: "
                + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
    }

    private static void writable(RuntimeHash self) {
        if (readOnly(self)) throw new PerlCompilerException("DBM database is read-only");
    }

    public static RuntimeList TIEHASH(RuntimeArray args, int ctx) {
        if (args.size() < 3) throw new PerlCompilerException("Usage: DBM::TIEHASH(filename, mode)");
        String filename = args.get(1).toString();
        long mode = args.get(2).getLong();
        boolean readOnly = mode == 0;
        try {
            if (readOnly && !java.nio.file.Files.exists(java.nio.file.Path.of(filename))) {
                return scalarUndef.getList();
            }
            Class.forName("org.sqlite.JDBC");
            String url = "jdbc:sqlite:" + filename;
            Connection connection = DriverManager.getConnection(url);
            if (!readOnly) {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("CREATE TABLE IF NOT EXISTS perlonjava_dbm "
                            + "(key BLOB PRIMARY KEY, value BLOB NOT NULL)");
                }
            }
            RuntimeHash state = new RuntimeHash();
            state.put(CONNECTION, new RuntimeScalar(connection));
            state.put(READ_ONLY, new RuntimeScalar(readOnly));
            state.put(KEYS, new RuntimeArray().createReference());
            state.put(KEY_INDEX, new RuntimeScalar(-1));
            RuntimeScalar reference = state.createReference();
            ReferenceOperators.bless(reference, new RuntimeScalar("PerlOnJava::DBM"));
            return reference.getList();
        } catch (Exception error) {
            throw failure("open", error);
        }
    }

    private static byte[] fetchBytes(Connection connection, byte[] key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT value FROM perlonjava_dbm WHERE key = ?")) {
            statement.setBytes(1, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getBytes(1) : null;
            }
        }
    }

    public static RuntimeList FETCH(RuntimeArray args, int ctx) {
        try {
            return scalar(fetchBytes(connection(self(args)), bytes(args.get(1)))).getList();
        } catch (SQLException error) {
            throw failure("fetch", error);
        }
    }

    public static RuntimeList STORE(RuntimeArray args, int ctx) {
        RuntimeHash state = self(args);
        writable(state);
        try (PreparedStatement statement = connection(state).prepareStatement(
                "INSERT INTO perlonjava_dbm(key, value) VALUES(?, ?) "
                        + "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            statement.setBytes(1, bytes(args.get(1)));
            statement.setBytes(2, bytes(args.get(2)));
            statement.executeUpdate();
            return new RuntimeList();
        } catch (SQLException error) {
            throw failure("store", error);
        }
    }

    public static RuntimeList DELETE(RuntimeArray args, int ctx) {
        RuntimeHash state = self(args);
        writable(state);
        try {
            byte[] old = fetchBytes(connection(state), bytes(args.get(1)));
            if (old == null) return scalarUndef.getList();
            try (PreparedStatement statement = connection(state).prepareStatement(
                    "DELETE FROM perlonjava_dbm WHERE key = ?")) {
                statement.setBytes(1, bytes(args.get(1)));
                statement.executeUpdate();
            }
            return scalar(old).getList();
        } catch (SQLException error) {
            throw failure("delete", error);
        }
    }

    public static RuntimeList CLEAR(RuntimeArray args, int ctx) {
        RuntimeHash state = self(args);
        writable(state);
        try (Statement statement = connection(state).createStatement()) {
            statement.executeUpdate("DELETE FROM perlonjava_dbm");
            return new RuntimeList();
        } catch (SQLException error) {
            throw failure("clear", error);
        }
    }

    public static RuntimeList EXISTS(RuntimeArray args, int ctx) {
        try (PreparedStatement statement = connection(self(args)).prepareStatement(
                "SELECT 1 FROM perlonjava_dbm WHERE key = ?")) {
            statement.setBytes(1, bytes(args.get(1)));
            try (ResultSet result = statement.executeQuery()) {
                return new RuntimeScalar(result.next()).getList();
            }
        } catch (SQLException error) {
            throw failure("exists", error);
        }
    }

    private static RuntimeArray loadKeys(RuntimeHash state) throws SQLException {
        RuntimeArray keys = new RuntimeArray();
        try (Statement statement = connection(state).createStatement();
             ResultSet result = statement.executeQuery("SELECT key FROM perlonjava_dbm ORDER BY rowid")) {
            while (result.next()) keys.push(scalar(result.getBytes(1)));
        }
        state.put(KEYS, keys.createReference());
        return keys;
    }

    public static RuntimeList FIRSTKEY(RuntimeArray args, int ctx) {
        RuntimeHash state = self(args);
        try {
            RuntimeArray keys = loadKeys(state);
            state.put(KEY_INDEX, new RuntimeScalar(0));
            return keys.isEmpty() ? scalarUndef.getList() : keys.get(0).getList();
        } catch (SQLException error) {
            throw failure("firstkey", error);
        }
    }

    public static RuntimeList NEXTKEY(RuntimeArray args, int ctx) {
        RuntimeHash state = self(args);
        RuntimeArray keys = state.get(KEYS).arrayDeref();
        int index = state.get(KEY_INDEX).getInt() + 1;
        state.put(KEY_INDEX, new RuntimeScalar(index));
        return index >= keys.size() ? scalarUndef.getList() : keys.get(index).getList();
    }

    public static RuntimeList SCALAR(RuntimeArray args, int ctx) {
        try (Statement statement = connection(self(args)).createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM perlonjava_dbm")) {
            return result.next() ? new RuntimeScalar(result.getLong(1)).getList() : new RuntimeScalar(0).getList();
        } catch (SQLException error) {
            throw failure("scalar", error);
        }
    }

    public static RuntimeList UNTIE(RuntimeArray args, int ctx) {
        RuntimeHash state = self(args);
        RuntimeScalar value = state.get(CONNECTION);
        try {
            if (value != null && value.value instanceof Connection connection) connection.close();
            state.put(CONNECTION, new RuntimeScalar());
            return scalarTrue.getList();
        } catch (SQLException error) {
            throw failure("close", error);
        }
    }
}
