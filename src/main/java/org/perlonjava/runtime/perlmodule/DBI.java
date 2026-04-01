package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.ReferenceOperators;
import org.perlonjava.runtime.operators.WarnDie;
import org.perlonjava.runtime.runtimetypes.*;

import java.sql.*;
import java.util.Enumeration;
import java.util.Properties;

import static org.perlonjava.runtime.runtimetypes.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.*;

/**
 * DBI (Database Independent Interface) module implementation for PerlonJava.
 * This class provides database connectivity and operations similar to Perl's DBI module.
 * <p>
 * Note: Some methods are defined in src/main/perl/lib/DBI.pm
 */
public class DBI extends PerlModuleBase {

    private static final int DBI_ERROR_CODE = 2000000000;  // Default $DBI::stderr value
    private static final String GENERAL_ERROR_STATE = "S1000";

    /**
     * Constructor initializes the DBI module.
     */
    public DBI() {
        super("DBI", false);
    }

    /**
     * Initializes and registers all DBI methods.
     * This method must be called before using any DBI functionality.
     */
    public static void initialize() {
        // Create new DBI instance
        DBI dbi = new DBI();
        try {
            // Register all supported DBI methods
            dbi.registerMethod("connect", null);
            dbi.registerMethod("prepare", null);
            dbi.registerMethod("execute", null);
            dbi.registerMethod("fetchrow_arrayref", null);
            dbi.registerMethod("fetchrow_hashref", null);
            dbi.registerMethod("rows", null);
            dbi.registerMethod("disconnect", null);
            dbi.registerMethod("last_insert_id", null);
            dbi.registerMethod("begin_work", null);
            dbi.registerMethod("commit", null);
            dbi.registerMethod("rollback", null);
            dbi.registerMethod("bind_param", null);
            dbi.registerMethod("bind_param_inout", null);
            dbi.registerMethod("bind_col", null);
            dbi.registerMethod("table_info", null);
            dbi.registerMethod("column_info", null);
            dbi.registerMethod("primary_key_info", null);
            dbi.registerMethod("foreign_key_info", null);
            dbi.registerMethod("type_info", null);
            dbi.registerMethod("ping", null);
            dbi.registerMethod("available_drivers", null);
            dbi.registerMethod("data_sources", null);
            dbi.registerMethod("get_info", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing DBI method: " + e.getMessage());
        }
    }

    /**
     * Executes a DBI operation with standardized error handling.
     *
     * @param operation  The operation to execute
     * @param handle     The database or statement handle for error context
     * @param methodName The name of the method being executed (for error messages)
     * @return RuntimeList result from the operation or error result
     */
    private static RuntimeList executeWithErrorHandling(DBIOperation operation, RuntimeHash handle, String methodName) {
        return executeWithErrorHandling(operation, handle, null, methodName);
    }

    private static RuntimeList executeWithErrorHandling(DBIOperation operation, RuntimeHash handle, RuntimeHash secondHandle, String methodName) {
        try {
            return operation.execute();
        } catch (SQLException e) {
            setError(handle, e);
            if (secondHandle != null) setError(secondHandle, e);
        } catch (Exception e) {
            SQLException sqlEx = new SQLException(e.getMessage(), GENERAL_ERROR_STATE, DBI_ERROR_CODE);
            setError(handle, sqlEx);
            if (secondHandle != null) setError(secondHandle, sqlEx);
        }
        RuntimeScalar msg = new RuntimeScalar("DBI " + methodName + "() failed: " + getGlobalVariable("DBI::errstr"));
        if (handle.get("RaiseError").getBoolean()) {
            throw new PerlCompilerException(msg.toString());
        }
        if (handle.get("PrintError").getBoolean()) {
            WarnDie.warn(msg, RuntimeScalarCache.scalarEmptyString);
        }
        return new RuntimeList();
    }

    /**
     * Establishes a database connection using the provided credentials.
     *
     * @param args RuntimeArray containing connection parameters:
     *             [0] - DBI object
     *             [1] - DSN (Data Source Name) in format "jdbc:protocol:database:host"
     *             [2] - Username
     *             [3] - Password
     *             [4] - \%attr (optional)
     * @param ctx  Context parameter
     * @return RuntimeList containing database handle (dbh)
     */
    public static RuntimeList connect(RuntimeArray args, int ctx) {
        RuntimeHash dbh = new RuntimeHash();
        String jdbcUrl = args.size() > 1 ? args.get(1).toString() : "";

        return executeWithErrorHandling(() -> {
            if (args.size() < 4) {
                throw new IllegalStateException("Bad number of arguments for DBI->connect");
            }

            // Extract connection parameters from args
            dbh.put("Username", new RuntimeScalar(args.get(2).toString()));
            dbh.put("Password", new RuntimeScalar(args.get(3).toString()));
            RuntimeScalar attr = args.get(4);   //  \%attr

            // Set dbh attributes
            dbh.put("ReadOnly", scalarFalse);
            dbh.put("AutoCommit", scalarTrue);

            // Handle credentials file if specified in attributes
            Properties props = new Properties();
            props.setProperty("user", dbh.get("Username").toString());
            props.setProperty("password", dbh.get("Password").toString());

            if (attr.type == RuntimeScalarType.HASHREFERENCE) {
                RuntimeHash attrHash = attr.hashDeref();
                if (attrHash.get("credentials").getDefinedBoolean()) {
                    String credentialsPath = attrHash.get("credentials").toString();
                    props.setProperty("credentials", credentialsPath);
                }
                // add other attributes to dbh
                dbh.setFromList(new RuntimeList(dbh, attrHash));
            }

            // Establish database connection with properties
            Connection conn = DriverManager.getConnection(jdbcUrl, props);

            // Remove password from dbh hash
            dbh.delete(new RuntimeScalar("Password"));

            // Create database handle (dbh) hash and store connection
            dbh.put("connection", new RuntimeScalar(conn));
            dbh.put("Active", new RuntimeScalar(true));
            dbh.put("Type", new RuntimeScalar("db"));
            dbh.put("Name", new RuntimeScalar(jdbcUrl));

            // Create blessed reference for Perl compatibility
            RuntimeScalar dbhRef = ReferenceOperators.bless(dbh.createReference(), new RuntimeScalar("DBI"));
            return dbhRef.getList();
        }, dbh, "connect('" + jdbcUrl + "','" + dbh.get("Username") + "',...) failed");
    }

    /**
     * Prepares an SQL statement for execution.
     *
     * @param args RuntimeArray containing:
     *             [0] - Database handle (dbh)
     *             [1] - SQL query string
     *             [2] - \%attr (optional)
     * @param ctx  Context parameter
     * @return RuntimeList containing statement handle (sth)
     */
    public static RuntimeList prepare(RuntimeArray args, int ctx) {
        RuntimeHash dbh = args.get(0).hashDeref();
        RuntimeHash sth = new RuntimeHash();
        sth.put("Database", dbh.createReference());

        return executeWithErrorHandling(() -> {
            if (args.size() < 2) {
                throw new IllegalStateException("Bad number of arguments for DBI->prepare");
            }

            // Extract database handle and SQL query
            String sql = args.get(1).toString();

            RuntimeScalar attr = args.get(2);   //  \%attr
            if (attr.type != RuntimeScalarType.HASHREFERENCE) {
                attr = new RuntimeHash().createReference();
            }
            // add attributes from dbh and attr: RaiseError, PrintError, FetchHashKeyName
            sth.setFromList(new RuntimeList(sth, dbh, attr.hashDeref()));

            // Get connection from database handle
            Connection conn = (Connection) dbh.get("connection").value;

            // Set AutoCommit attribute in case it was changed
            conn.setAutoCommit(dbh.get("AutoCommit").getBoolean());

            // Set ReadOnly attribute in case it was changed
            conn.setReadOnly(sth.get("ReadOnly").getBoolean());

            // Prepare statement
            PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);

            // Create statement handle (sth) hash
            sth.put("statement", new RuntimeScalar(stmt));
            sth.put("sql", new RuntimeScalar(sql));
            sth.put("Type", new RuntimeScalar("st"));

            // Add NUM_OF_FIELDS by getting metadata
            int numFields = 0;
            try {
                ResultSetMetaData metaData = stmt.getMetaData();
                if (metaData != null) {
                    numFields = metaData.getColumnCount();
                }
            } catch (Exception e) {
                // Some drivers (e.g. sqlite-jdbc) throw on DDL statements
            }
            sth.put("NUM_OF_FIELDS", new RuntimeScalar(numFields));

            // Add NUM_OF_PARAMS by getting parameter count
            int numParams = 0;
            try {
                ParameterMetaData paramMetaData = stmt.getParameterMetaData();
                numParams = paramMetaData.getParameterCount();
            } catch (Exception e) {
                // Some drivers (e.g. sqlite-jdbc) throw on DDL/non-parameterized statements
            }
            sth.put("NUM_OF_PARAMS", new RuntimeScalar(numParams));

            // Create blessed reference for statement handle
            RuntimeScalar sthRef = ReferenceOperators.bless(sth.createReference(), new RuntimeScalar("DBI"));

            dbh.get("sth").set(sthRef);

            return sthRef.getList();
        }, dbh, "prepare");
    }

    public static RuntimeList last_insert_id(RuntimeArray args, int ctx) {
        // argument can be either a dbh or a sth
        RuntimeHash dbh = args.get(0).hashDeref();
        RuntimeHash sth = null;
        String type = dbh.get("Type").toString();
        if (type.equals("db")) {
            sth = dbh.get("sth").hashDeref();
        } else {
            sth = dbh;
            dbh = sth.get("Database").hashDeref();
        }

        final RuntimeHash finalDbh = dbh;
        final RuntimeHash finalSth = sth;
        return executeWithErrorHandling(() -> {
            Connection conn = (Connection) finalDbh.get("connection").value;
            Statement stmt = (Statement) finalSth.get("statement").value;
            ResultSet rs = stmt.getGeneratedKeys();

            if (rs.next()) {
                long id = rs.getLong(1);
                return new RuntimeScalar(id).getList();
            }

            return scalarUndef.getList();
        }, finalDbh, "last_insert_id");
    }

    /**
     * Executes a prepared statement with optional parameters.
     *
     * @param args RuntimeArray containing:
     *             [0] - Statement handle (sth)
     *             [1..n] - Optional bind parameters
     * @param ctx  Context parameter
     * @return RuntimeList containing execution result
     */
    public static RuntimeList execute(RuntimeArray args, int ctx) {
        RuntimeHash sth = args.get(0).hashDeref();
        RuntimeHash dbh = sth.get("Database").hashDeref();

        // Clear previous error state on sth before executing
        setError(sth, null);

        return executeWithErrorHandling(() -> {
            if (args.isEmpty()) {
                throw new IllegalStateException("Bad number of arguments for DBI->execute");
            }

            // Metadata statement handles (from column_info, table_info, etc.)
            // don't have a PreparedStatement — result set is already available
            RuntimeScalar stmtScalar = sth.get("statement");
            if (stmtScalar == null || stmtScalar.type == RuntimeScalarType.UNDEF) {
                // Already "executed" by the metadata method — just return success
                sth.put("Executed", scalarTrue);
                dbh.put("Executed", scalarTrue);
                return new RuntimeScalar(-1).getList();
            }

            // Get prepared statement from statement handle
            PreparedStatement stmt = (PreparedStatement) stmtScalar.value;

            // Detect literal transaction SQL before execution
            // Strip leading comments (-- comment\n) for detection
            String sql = sth.get("sql").toString().trim();
            String strippedSql = sql;
            while (strippedSql.startsWith("--")) {
                int nlIdx = strippedSql.indexOf('\n');
                if (nlIdx >= 0) {
                    strippedSql = strippedSql.substring(nlIdx + 1).trim();
                } else {
                    break;
                }
            }
            String sqlUpper = strippedSql.toUpperCase();
            boolean isBegin = sqlUpper.startsWith("BEGIN");
            boolean isCommit = sqlUpper.startsWith("COMMIT") || sqlUpper.startsWith("END");
            boolean isRollback = sqlUpper.startsWith("ROLLBACK") && !sqlUpper.contains("SAVEPOINT");

            Connection conn = (Connection) dbh.get("connection").value;

            // Intercept transaction control SQL — use JDBC API instead of executing
            // as SQL, because JDBC drivers (especially SQLite) don't handle literal
            // BEGIN/COMMIT/ROLLBACK properly when autocommit is enabled
            if (isBegin || isCommit || isRollback) {
                if (isBegin) {
                    conn.setAutoCommit(false);
                    dbh.put("AutoCommit", scalarFalse);
                } else if (isCommit) {
                    conn.commit();
                    conn.setAutoCommit(true);
                    dbh.put("AutoCommit", scalarTrue);
                } else {
                    conn.rollback();
                    conn.setAutoCommit(true);
                    dbh.put("AutoCommit", scalarTrue);
                }
                sth.put("Executed", scalarTrue);
                dbh.put("Executed", scalarTrue);
                RuntimeHash result = new RuntimeHash();
                result.put("success", scalarTrue);
                result.put("has_resultset", scalarFalse);
                sth.put("execute_result", result.createReference());
                return new RuntimeScalar("0E0").getList();
            }

            // Bind parameters and execute the statement.
            // If the JDBC PreparedStatement is stale (e.g., invalidated by ROLLBACK),
            // re-prepare it and retry once.
            boolean retried = false;
            boolean hasResultSet = false;
            while (true) {
                try {
                    // Bind parameters to prepared statement
                    if (args.size() > 1) {
                        // Inline parameters passed to execute(@bind_values)
                        for (int i = 1; i < args.size(); i++) {
                            stmt.setObject(i, args.get(i).value);
                        }
                    } else {
                        // Apply stored bound_params from bind_param() calls
                        RuntimeScalar boundParamsRef = sth.get("bound_params");
                        if (boundParamsRef != null && RuntimeScalarType.isReference(boundParamsRef)) {
                            RuntimeHash boundParams = boundParamsRef.hashDeref();
                            for (RuntimeScalar key : boundParams.keys().elements) {
                                int paramIndex = Integer.parseInt(key.toString());
                                RuntimeScalar val = boundParams.get(key.toString());
                                stmt.setObject(paramIndex, val.value);
                            }
                        }
                    }

                    // Execute the statement and check for result set
                    hasResultSet = stmt.execute();
                    break; // Success — exit retry loop
                } catch (SQLException e) {
                    // SQLite JDBC invalidates PreparedStatements after ROLLBACK.
                    // Re-prepare the statement and retry once.
                    if (!retried && e.getMessage() != null
                            && e.getMessage().contains("not executing")) {
                        retried = true;
                        stmt = conn.prepareStatement(sql);
                        sth.put("statement", new RuntimeScalar(stmt));
                        continue; // Retry with fresh statement
                    }
                    throw e; // Rethrow other errors
                }
            }

            // Create result hash with execution status
            RuntimeHash result = new RuntimeHash();
            result.put("success", new RuntimeScalar(true));
            result.put("has_resultset", new RuntimeScalar(hasResultSet));

            if (hasResultSet) {
                // Store result set if available
                ResultSet rs = stmt.getResultSet();
                result.put("resultset", new RuntimeScalar(rs));

                // Get column metadata
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();

                // Create arrays for column names
                RuntimeArray columnNames = new RuntimeArray();
                RuntimeArray columnNamesLower = new RuntimeArray();
                RuntimeArray columnNamesUpper = new RuntimeArray();

                // Populate column name arrays
                for (int i = 1; i <= columnCount; i++) {
                    // String name = metaData.getColumnName(i);
                    String name = metaData.getColumnLabel(i);
                    RuntimeArray.push(columnNames, new RuntimeScalar(name));
                    RuntimeArray.push(columnNamesLower, new RuntimeScalar(name.toLowerCase()));
                    RuntimeArray.push(columnNamesUpper, new RuntimeScalar(name.toUpperCase()));
                }

                // Store column name arrays in statement handle
                sth.put("NAME", columnNames.createReference());
                sth.put("NAME_lc", columnNamesLower.createReference());
                sth.put("NAME_uc", columnNamesUpper.createReference());
            }

            sth.put("Executed", scalarTrue);
            dbh.put("Executed", scalarTrue);

            // Store execution result in statement handle
            sth.put("execute_result", result.createReference());

            // Return value per DBI spec:
            // - For DML (INSERT/UPDATE/DELETE): number of rows affected, or "0E0" for 0 rows
            // - For SELECT: -1 (unknown number of rows)
            if (hasResultSet) {
                return new RuntimeScalar(-1).getList();
            } else {
                int updateCount = stmt.getUpdateCount();
                if (updateCount == 0) {
                    return new RuntimeScalar("0E0").getList();
                }
                return new RuntimeScalar(updateCount).getList();
            }
        }, dbh, sth, "execute");
    }

    /**
     * Fetches the next row from a result set as an array.
     *
     * @param args RuntimeArray containing statement handle (sth)
     * @param ctx  Context parameter
     * @return RuntimeList containing row data or empty list if no more rows
     */
    public static RuntimeList fetchrow_arrayref(RuntimeArray args, int ctx) {
        // Get statement handle and result set
        RuntimeHash sth = args.get(0).hashDeref();
        RuntimeHash dbh = sth.get("Database").hashDeref();

        return executeWithErrorHandling(() -> {
            RuntimeHash executeResult = sth.get("execute_result").hashDeref();
            ResultSet rs = (ResultSet) executeResult.get("resultset").value;

            // Fetch next row if available
            if (rs.next()) {
                RuntimeArray row = new RuntimeArray();
                ResultSetMetaData metaData = rs.getMetaData();
                int colCount = metaData.getColumnCount();
                // Convert each column value to string and add to row array
                for (int i = 1; i <= colCount; i++) {
                    RuntimeArray.push(row, RuntimeScalar.newScalarOrString(rs.getObject(i)));
                }

                // Update bound columns if any (for bind_columns + fetch pattern)
                RuntimeScalar boundRef = sth.get("bound_columns");
                if (boundRef != null && boundRef.type != RuntimeScalarType.UNDEF) {
                    RuntimeHash boundColumns = boundRef.hashDeref();
                    for (int i = 1; i <= colCount; i++) {
                        RuntimeScalar ref = boundColumns.get(String.valueOf(i));
                        if (ref != null && ref.type != RuntimeScalarType.UNDEF) {
                            // Dereference the scalar ref and set its value
                            ref.scalarDeref().set(row.get(i - 1));
                        }
                    }
                }

                return row.createReference().getList();
            }

            // Return empty array if no more rows
            return new RuntimeList();
        }, dbh, "fetchrow_arrayref");
    }

    /**
     * Fetches the next row from a result set as a hash reference.
     *
     * @param args RuntimeArray containing statement handle (sth)
     * @param ctx  Context parameter
     * @return RuntimeList containing row data as hash reference or empty hash if no more rows
     */
    public static RuntimeList fetchrow_hashref(RuntimeArray args, int ctx) {
        RuntimeHash sth = args.get(0).hashDeref();
        RuntimeHash dbh = sth.get("Database").hashDeref();

        return executeWithErrorHandling(() -> {
            // Handle pre-fetched rows (from PRAGMA-based column_info, etc.)
            RuntimeScalar pragmaRows = sth.get("_pragma_rows");
            if (pragmaRows != null && pragmaRows.type != RuntimeScalarType.UNDEF) {
                RuntimeArray rows = pragmaRows.arrayDeref();
                int idx = sth.get("_pragma_idx").getInt();
                if (idx < rows.size()) {
                    sth.put("_pragma_idx", new RuntimeScalar(idx + 1));
                    RuntimeHash row = rows.get(idx).hashDeref();
                    return row.createReference().getList();
                }
                return scalarUndef.getList();
            }

            RuntimeHash executeResult = sth.get("execute_result").hashDeref();
            ResultSet rs = (ResultSet) executeResult.get("resultset").value;

            // Fetch next row if available
            if (rs.next()) {
                RuntimeHash row = new RuntimeHash();
                ResultSetMetaData metaData = rs.getMetaData();

                // Get the column name style to use
                String nameStyle = sth.get("FetchHashKeyName").toString();
                if (nameStyle.isEmpty()) {
                    nameStyle = "NAME";
                }
                RuntimeArray columnNames = sth.get(nameStyle).arrayDeref();

                // For each column, add column name -> value pair to hash
                for (int i = 1; i <= metaData.getColumnCount(); i++) {
                    String columnName = columnNames.get(i - 1).toString();
                    Object value = rs.getObject(i);
                    row.put(columnName, RuntimeScalar.newScalarOrString(value));
                }

                // Create reference for hash
                RuntimeScalar rowRef = row.createReference();
                return rowRef.getList();
            }

            // Return undef if no more rows
            return scalarUndef.getList();
        }, dbh, "fetchrow_hashref");
    }

    /**
     * Returns the number of rows affected by the last statement execution.
     *
     * @param args RuntimeArray containing statement handle (sth)
     * @param ctx  Context parameter
     * @return RuntimeList containing row count (-1 if not available)
     */
    public static RuntimeList rows(RuntimeArray args, int ctx) {
        RuntimeHash sth = args.get(0).hashDeref();

        return executeWithErrorHandling(() -> {
            if (args.isEmpty()) {
                throw new IllegalStateException("Bad number of arguments for DBI->rows");
            }

            // Get statement handle
            PreparedStatement stmt = (PreparedStatement) sth.get("statement").value;
            int rowCount = -1;

            // Try to get update count for DML operations
            try {
                rowCount = stmt.getUpdateCount();
            } catch (SQLException e) {
                return new RuntimeArray(new RuntimeScalar(-1)).getList();
            }

            // For SELECT queries, try to get result set row count
            if (rowCount == -1 && sth.elements.containsKey("resultset")) {
                ResultSet rs = (ResultSet) sth.get("resultset").value;
                try {
                    // Move to last row to get total count
                    if (rs.last()) {
                        rowCount = rs.getRow();
                        // Reset cursor position
                        rs.beforeFirst();
                    }
                } catch (SQLException e) {
                    rowCount = -1;
                }
            }

            return new RuntimeArray(new RuntimeScalar(rowCount)).getList();
        }, sth, "rows");
    }

    /**
     * Closes the database connection and marks it as inactive.
     *
     * @param args RuntimeArray containing database handle (dbh)
     * @param ctx  Context parameter
     * @return RuntimeList containing empty hash reference
     */
    public static RuntimeList disconnect(RuntimeArray args, int ctx) {
        RuntimeHash dbh = args.get(0).hashDeref();

        return executeWithErrorHandling(() -> {
            Connection conn = (Connection) dbh.get("connection").value;
            String name = dbh.get("Name").toString();

            conn.close();
            dbh.put("Active", new RuntimeScalar(false));

            return new RuntimeHash().createReference().getList();
        }, dbh, "disconnect");
    }

    /**
     * Internal method to set error information on a handle.
     *
     * @param handle    The database or statement handle
     * @param exception The SQL exception that occurred
     */
    /**
     * Normalizes JDBC error messages to match native driver format.
     * JDBC drivers (especially SQLite) wrap error messages with extra context:
     *   "[SQLITE_MISMATCH] Data type mismatch (datatype mismatch)"
     * Native drivers return just the core message:
     *   "datatype mismatch"
     * This method extracts the parenthesized message if present.
     */
    private static String normalizeErrorMessage(String message) {
        if (message == null) return null;
        // Match pattern: "[CODE] Description (actual message)" -> "actual message"
        // The parenthesized part at the end is the native error message
        int lastOpen = message.lastIndexOf('(');
        int lastClose = message.lastIndexOf(')');
        if (lastOpen >= 0 && lastClose > lastOpen && message.startsWith("[")) {
            return message.substring(lastOpen + 1, lastClose);
        }
        return message;
    }

    private static void setError(RuntimeHash handle, SQLException exception) {
        if (exception != null) {
            handle.put("err", new RuntimeScalar(exception.getErrorCode()));
            handle.put("errstr", new RuntimeScalar(normalizeErrorMessage(exception.getMessage())));
            handle.put("state", new RuntimeScalar(exception.getSQLState() != null ?
                    exception.getSQLState() : GENERAL_ERROR_STATE));
        } else {
            // Clear error state
            handle.put("err", scalarUndef);
            handle.put("errstr", new RuntimeScalar(""));
            handle.put("state", new RuntimeScalar("00000"));
        }
        getGlobalVariable("DBI::err").set(handle.get("err"));
        getGlobalVariable("DBI::errstr").set(handle.get("errstr"));
    }

    public static RuntimeList begin_work(RuntimeArray args, int ctx) {
        RuntimeHash dbh = args.get(0).hashDeref();

        return executeWithErrorHandling(() -> {
            Connection conn = (Connection) dbh.get("connection").value;
            conn.setAutoCommit(false);
            dbh.put("AutoCommit", scalarFalse);
            return scalarTrue.getList();
        }, dbh, "begin_work");
    }

    public static RuntimeList commit(RuntimeArray args, int ctx) {
        RuntimeHash dbh = args.get(0).hashDeref();

        return executeWithErrorHandling(() -> {
            Connection conn = (Connection) dbh.get("connection").value;
            conn.commit();
            conn.setAutoCommit(true);
            dbh.put("AutoCommit", scalarTrue);
            return scalarTrue.getList();
        }, dbh, "commit");
    }

    public static RuntimeList rollback(RuntimeArray args, int ctx) {
        RuntimeHash dbh = args.get(0).hashDeref();

        return executeWithErrorHandling(() -> {
            Connection conn = (Connection) dbh.get("connection").value;
            conn.rollback();
            conn.setAutoCommit(true);
            dbh.put("AutoCommit", scalarTrue);
            return scalarTrue.getList();
        }, dbh, "rollback");
    }

    public static RuntimeList bind_param(RuntimeArray args, int ctx) {
        RuntimeHash sth = args.get(0).hashDeref();
        RuntimeHash dbh = sth.get("Database").hashDeref();

        return executeWithErrorHandling(() -> {
            if (args.size() < 3) {
                throw new IllegalStateException("Bad number of arguments for DBI->bind_param");
            }

            int paramIndex = args.get(1).getInt();
            Object value = args.get(2).value;

            // Store bound parameters for later use (applied during execute())
            RuntimeHash boundParams = sth.get("bound_params") != null ?
                    sth.get("bound_params").hashDeref() : new RuntimeHash();
            boundParams.put(String.valueOf(paramIndex), new RuntimeScalar(value));
            sth.put("bound_params", boundParams.createReference());

            // Store bind attributes if provided (4th arg is attrs hashref or type int)
            if (args.size() >= 4) {
                RuntimeHash boundAttrs = sth.get("bound_attrs") != null ?
                        sth.get("bound_attrs").hashDeref() : new RuntimeHash();
                boundAttrs.put(String.valueOf(paramIndex), args.get(3));
                sth.put("bound_attrs", boundAttrs.createReference());
            }

            // Don't call stmt.setObject() here - params are applied in execute()
            return scalarTrue.getList();
        }, dbh, "bind_param");
    }

    public static RuntimeList bind_param_inout(RuntimeArray args, int ctx) {
        RuntimeHash sth = args.get(0).hashDeref();
        RuntimeHash dbh = sth.get("Database").hashDeref();

        return executeWithErrorHandling(() -> {
            if (args.size() < 3) {
                throw new IllegalStateException("Bad number of arguments for DBI->bind_param_inout");
            }

            int paramIndex = args.get(1).getInt();
            RuntimeScalar valueRef = args.get(2);

            // Store bound parameters for later use (applied during execute())
            RuntimeHash boundParams = sth.get("bound_params") != null ?
                    sth.get("bound_params").hashDeref() : new RuntimeHash();
            boundParams.put(String.valueOf(paramIndex), valueRef);
            sth.put("bound_params", boundParams.createReference());

            // Don't call stmt.setObject() here - params are applied in execute()
            return scalarTrue.getList();
        }, dbh, "bind_param_inout");
    }

    public static RuntimeList bind_col(RuntimeArray args, int ctx) {
        RuntimeHash sth = args.get(0).hashDeref();
        RuntimeHash dbh = sth.get("Database").hashDeref();

        return executeWithErrorHandling(() -> {
            if (args.size() < 2) {
                throw new IllegalStateException("Bad number of arguments for DBI->bind_col");
            }

            int colIndex = args.get(1).getInt();
            RuntimeScalar valueRef = args.size() > 2 ? args.get(2) : null;

            // Store bound columns for later use
            RuntimeHash boundColumns = sth.get("bound_columns") != null ?
                    sth.get("bound_columns").hashDeref() : new RuntimeHash();
            boundColumns.put(String.valueOf(colIndex), valueRef);
            sth.put("bound_columns", boundColumns.createReference());

            return scalarTrue.getList();
        }, dbh, "bind_col");
    }

    public static RuntimeList table_info(RuntimeArray args, int ctx) {
        RuntimeHash dbh = args.get(0).hashDeref();

        return executeWithErrorHandling(() -> {
            Connection conn = (Connection) dbh.get("connection").value;
            DatabaseMetaData metaData = conn.getMetaData();

            String catalog = args.size() > 1 ? args.get(1).toString() : null;
            String schema = args.size() > 2 ? args.get(2).toString() : null;
            String table = args.size() > 3 ? args.get(3).toString() : "%";
            String type = args.size() > 4 ? args.get(4).toString() : null;

            ResultSet rs = metaData.getTables(catalog, schema, table, type != null ? new String[]{type} : null);

            // Create statement handle for results
            RuntimeHash sth = createMetadataResultSet(dbh, rs);
            RuntimeScalar sthRef = ReferenceOperators.bless(sth.createReference(), new RuntimeScalar("DBI"));
            return sthRef.getList();
        }, dbh, "table_info");
    }

    public static RuntimeList column_info(RuntimeArray args, int ctx) {
        RuntimeHash dbh = args.get(0).hashDeref();

        return executeWithErrorHandling(() -> {
            if (args.size() < 4) {
                throw new IllegalStateException("Bad number of arguments for DBI->column_info");
            }

            Connection conn = (Connection) dbh.get("connection").value;
            String table = args.get(3).toString();

            // For SQLite, use PRAGMA table_info() to preserve original type case
            // (JDBC getColumns() uppercases type names like varchar -> VARCHAR)
            String jdbcUrl = dbh.get("Name").toString();
            if (jdbcUrl.contains("sqlite")) {
                return columnInfoViaPragma(dbh, conn, table);
            }

            DatabaseMetaData metaData = conn.getMetaData();

            // Handle undef args: pass null to JDBC (means "any") instead of ""
            String catalog = args.get(1).type == RuntimeScalarType.UNDEF ? null : args.get(1).toString();
            String schema = args.get(2).type == RuntimeScalarType.UNDEF ? null : args.get(2).toString();
            String column = args.size() > 4 ? args.get(4).toString() : "%";

            ResultSet rs = metaData.getColumns(catalog, schema, table, column);

            RuntimeHash sth = createMetadataResultSet(dbh, rs);
            RuntimeScalar sthRef = ReferenceOperators.bless(sth.createReference(), new RuntimeScalar("DBI"));
            return sthRef.getList();
        }, dbh, "column_info");
    }

    /**
     * SQLite-specific column_info using PRAGMA table_info().
     * Preserves original type case from CREATE TABLE (e.g., "varchar" not "VARCHAR").
     * Returns a pre-populated sth with DBI-standard column names.
     */
    private static RuntimeList columnInfoViaPragma(RuntimeHash dbh, Connection conn, String table) throws SQLException {
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")");

        // Build arrays of rows matching DBI column_info format
        RuntimeArray rows = new RuntimeArray();
        while (rs.next()) {
            RuntimeHash row = new RuntimeHash();
            String colName = rs.getString("name");
            String declType = rs.getString("type");
            int notNull = rs.getInt("notnull");
            String dfltValue = rs.getString("dflt_value");
            int pk = rs.getInt("pk");

            // Parse declared type: "varchar(100)" -> type="varchar", size=100
            String typeName = declType;
            String columnSize = null;
            if (declType != null) {
                int parenIdx = declType.indexOf('(');
                if (parenIdx >= 0) {
                    typeName = declType.substring(0, parenIdx);
                    int closeIdx = declType.indexOf(')', parenIdx);
                    if (closeIdx > parenIdx) {
                        columnSize = declType.substring(parenIdx + 1, closeIdx);
                    }
                }
            }

            row.put("COLUMN_NAME", new RuntimeScalar(colName));
            row.put("TYPE_NAME", new RuntimeScalar(typeName != null ? typeName : ""));
            row.put("COLUMN_SIZE", columnSize != null ? new RuntimeScalar(columnSize) : scalarUndef);
            // DBI NULLABLE: 0=not nullable, 1=nullable, 2=unknown
            row.put("NULLABLE", new RuntimeScalar(notNull != 0 ? 0 : 1));
            row.put("COLUMN_DEF", dfltValue != null ? new RuntimeScalar(dfltValue) : scalarUndef);
            row.put("ORDINAL_POSITION", new RuntimeScalar(rs.getInt("cid") + 1));

            RuntimeArray.push(rows, row.createReference());
        }
        rs.close();
        stmt.close();

        // Create a synthetic sth that yields these rows via fetchrow_hashref
        RuntimeHash sth = new RuntimeHash();
        sth.put("Database", dbh.createReference());
        sth.put("Type", new RuntimeScalar("st"));
        sth.put("Executed", scalarTrue);

        // Inherit error handling and fetch settings from dbh
        RuntimeScalar raiseError = dbh.get("RaiseError");
        if (raiseError != null) sth.put("RaiseError", raiseError);
        RuntimeScalar printError = dbh.get("PrintError");
        if (printError != null) sth.put("PrintError", printError);

        // Store pre-fetched rows for iteration
        sth.put("_pragma_rows", rows.createReference());
        sth.put("_pragma_idx", new RuntimeScalar(0));

        // Set up column names for the result set
        String[] colNames = {"COLUMN_NAME", "TYPE_NAME", "COLUMN_SIZE", "NULLABLE", "COLUMN_DEF", "ORDINAL_POSITION"};
        RuntimeArray names = new RuntimeArray();
        RuntimeArray namesLc = new RuntimeArray();
        RuntimeArray namesUc = new RuntimeArray();
        for (String n : colNames) {
            RuntimeArray.push(names, new RuntimeScalar(n));
            RuntimeArray.push(namesLc, new RuntimeScalar(n.toLowerCase()));
            RuntimeArray.push(namesUc, new RuntimeScalar(n.toUpperCase()));
        }
        sth.put("NAME", names.createReference());
        sth.put("NAME_lc", namesLc.createReference());
        sth.put("NAME_uc", namesUc.createReference());
        sth.put("NUM_OF_FIELDS", new RuntimeScalar(colNames.length));

        // Create a dummy execute_result with no JDBC resultset
        RuntimeHash result = new RuntimeHash();
        result.put("success", scalarTrue);
        result.put("has_resultset", scalarTrue);
        sth.put("execute_result", result.createReference());

        RuntimeScalar sthRef = ReferenceOperators.bless(sth.createReference(), new RuntimeScalar("DBI"));
        return sthRef.getList();
    }

    public static RuntimeList primary_key_info(RuntimeArray args, int ctx) {
        RuntimeHash dbh = args.get(0).hashDeref();

        return executeWithErrorHandling(() -> {
            if (args.size() < 4) {
                throw new IllegalStateException("Bad number of arguments for DBI->primary_key_info");
            }

            Connection conn = (Connection) dbh.get("connection").value;
            DatabaseMetaData metaData = conn.getMetaData();

            String catalog = args.get(1).toString();
            String schema = args.get(2).toString();
            String table = args.get(3).toString();

            ResultSet rs = metaData.getPrimaryKeys(catalog, schema, table);

            RuntimeHash sth = createMetadataResultSet(dbh, rs);
            RuntimeScalar sthRef = ReferenceOperators.bless(sth.createReference(), new RuntimeScalar("DBI"));
            return sthRef.getList();
        }, dbh, "primary_key_info");
    }

    public static RuntimeList foreign_key_info(RuntimeArray args, int ctx) {
        RuntimeHash dbh = args.get(0).hashDeref();

        return executeWithErrorHandling(() -> {
            if (args.size() < 7) {
                throw new IllegalStateException("Bad number of arguments for DBI->foreign_key_info");
            }

            Connection conn = (Connection) dbh.get("connection").value;
            DatabaseMetaData metaData = conn.getMetaData();

            String pkCatalog = args.get(1).toString();
            String pkSchema = args.get(2).toString();
            String pkTable = args.get(3).toString();
            String fkCatalog = args.get(4).toString();
            String fkSchema = args.get(5).toString();
            String fkTable = args.get(6).toString();

            ResultSet rs = metaData.getCrossReference(pkCatalog, pkSchema, pkTable,
                    fkCatalog, fkSchema, fkTable);

            RuntimeHash sth = createMetadataResultSet(dbh, rs);
            RuntimeScalar sthRef = ReferenceOperators.bless(sth.createReference(), new RuntimeScalar("DBI"));
            return sthRef.getList();
        }, dbh, "foreign_key_info");
    }

    public static RuntimeList type_info(RuntimeArray args, int ctx) {
        RuntimeHash dbh = args.get(0).hashDeref();

        return executeWithErrorHandling(() -> {
            Connection conn = (Connection) dbh.get("connection").value;
            DatabaseMetaData metaData = conn.getMetaData();
            ResultSet rs = metaData.getTypeInfo();

            RuntimeHash sth = createMetadataResultSet(dbh, rs);
            RuntimeScalar sthRef = ReferenceOperators.bless(sth.createReference(), new RuntimeScalar("DBI"));
            return sthRef.getList();
        }, dbh, "type_info");
    }

    private static RuntimeHash createMetadataResultSet(RuntimeHash dbh, ResultSet rs) throws SQLException {
        RuntimeHash sth = new RuntimeHash();
        sth.put("Database", dbh.createReference());

        // Inherit error handling and fetch settings from dbh
        RuntimeScalar raiseError = dbh.get("RaiseError");
        if (raiseError != null) sth.put("RaiseError", raiseError);
        RuntimeScalar printError = dbh.get("PrintError");
        if (printError != null) sth.put("PrintError", printError);
        RuntimeScalar fetchKeyName = dbh.get("FetchHashKeyName");
        if (fetchKeyName != null) sth.put("FetchHashKeyName", fetchKeyName);

        // Create statement handle with result set
        RuntimeHash result = new RuntimeHash();
        result.put("success", scalarTrue);
        result.put("has_resultset", scalarTrue);
        result.put("resultset", new RuntimeScalar(rs));

        // Get column metadata
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();

        // Create arrays for column names
        RuntimeArray columnNames = new RuntimeArray();
        RuntimeArray columnNamesLower = new RuntimeArray();
        RuntimeArray columnNamesUpper = new RuntimeArray();

        for (int i = 1; i <= columnCount; i++) {
            String name = metaData.getColumnLabel(i);
            RuntimeArray.push(columnNames, new RuntimeScalar(name));
            RuntimeArray.push(columnNamesLower, new RuntimeScalar(name.toLowerCase()));
            RuntimeArray.push(columnNamesUpper, new RuntimeScalar(name.toUpperCase()));
        }

        sth.put("NAME", columnNames.createReference());
        sth.put("NAME_lc", columnNamesLower.createReference());
        sth.put("NAME_uc", columnNamesUpper.createReference());
        sth.put("NUM_OF_FIELDS", new RuntimeScalar(columnCount));
        sth.put("Type", new RuntimeScalar("st"));
        sth.put("Executed", scalarTrue);
        sth.put("execute_result", result.createReference());

        // Bless into DBI so method calls like $sth->fetchrow_hashref() work
        return sth;
    }

    public static RuntimeList ping(RuntimeArray args, int ctx) {
        RuntimeHash dbh = args.get(0).hashDeref();

        return executeWithErrorHandling(() -> {
            Connection conn = (Connection) dbh.get("connection").value;
            return new RuntimeScalar(conn.isValid(5)).getList(); // 5 second timeout
        }, dbh, "ping");
    }

    public static RuntimeList available_drivers(RuntimeArray args, int ctx) {
        RuntimeArray drivers = new RuntimeArray();
        try {
            Enumeration<Driver> driverList = DriverManager.getDrivers();
            while (driverList.hasMoreElements()) {
                Driver driver = driverList.nextElement();
                RuntimeArray.push(drivers, new RuntimeScalar(driver.getClass().getName()));
            }
        } catch (Exception e) {
            // Return empty list if error occurs
        }
        return drivers.getList();
    }

    public static RuntimeList data_sources(RuntimeArray args, int ctx) {
        RuntimeArray sources = new RuntimeArray();
        String driverClass = !args.isEmpty() ? args.get(0).toString() : null;

        try {
            if (driverClass != null) {
                // Load specific driver
                Class.forName(driverClass);
            }

            // Get registered drivers
            Enumeration<Driver> drivers = DriverManager.getDrivers();
            while (drivers.hasMoreElements()) {
                Driver driver = drivers.nextElement();
                if (driverClass == null || driver.getClass().getName().equals(driverClass)) {
                    // Add standard URLs for common databases
                    if (driver.getClass().getName().contains("h2")) {
                        RuntimeArray.push(sources, new RuntimeScalar("jdbc:h2:mem:"));
                        RuntimeArray.push(sources, new RuntimeScalar("jdbc:h2:file:"));
                    } else if (driver.getClass().getName().contains("mysql")) {
                        RuntimeArray.push(sources, new RuntimeScalar("jdbc:mysql://localhost:3306/"));
                    } else if (driver.getClass().getName().contains("postgresql")) {
                        RuntimeArray.push(sources, new RuntimeScalar("jdbc:postgresql://localhost:5432/"));
                    }
                }
            }
        } catch (Exception e) {
            // Return current list if error occurs
        }
        return sources.getList();
    }

    public static RuntimeList get_info(RuntimeArray args, int ctx) {
        RuntimeHash dbh = args.get(0).hashDeref();
        int infoType = args.size() > 1 ? args.get(1).getInt() : -1;

        return executeWithErrorHandling(() -> {
            Connection conn = (Connection) dbh.get("connection").value;
            DatabaseMetaData meta = conn.getMetaData();

            // DBI get_info() takes a numeric SQL info type constant and returns a scalar.
            // Standard DBI::Const::GetInfoType constants:
            //   6  = SQL_DRIVER_NAME
            //   7  = SQL_DRIVER_VER
            //  17  = SQL_DBMS_NAME
            //  18  = SQL_DBMS_VER
            //  29  = SQL_IDENTIFIER_QUOTE_CHAR
            //  41  = SQL_CATALOG_NAME_SEPARATOR
            //  47  = SQL_USER_NAME
            //  89  = SQL_KEYWORDS
            // 112  = SQL_NUMERIC_FUNCTIONS
            // 116  = SQL_MAX_CONNECTIONS (0 = no limit)
            // 119  = SQL_STRING_FUNCTIONS
            String result;
            switch (infoType) {
                case 6:
                    result = meta.getDriverName();
                    break;
                case 7:
                    result = meta.getDriverVersion();
                    break;
                case 17:
                    result = meta.getDatabaseProductName();
                    break;
                case 18:
                    result = meta.getDatabaseProductVersion();
                    break;
                case 29:
                    result = meta.getIdentifierQuoteString();
                    break;
                case 41:
                    result = meta.getCatalogSeparator();
                    break;
                case 47:
                    result = meta.getUserName();
                    break;
                case 89:
                    result = meta.getSQLKeywords();
                    break;
                case 112:
                    result = meta.getNumericFunctions();
                    break;
                case 116:
                    return new RuntimeScalar(meta.getMaxConnections()).getList();
                case 119:
                    result = meta.getStringFunctions();
                    break;
                default:
                    return new RuntimeScalar().getList();
            }
            return RuntimeScalar.newScalarOrString(result != null ? result : "").getList();
        }, dbh, "get_info");
    }

    /**
     * Functional interface for DBI operations that can throw exceptions.
     */
    @FunctionalInterface
    private interface DBIOperation {
        RuntimeList execute() throws Exception;
    }
}