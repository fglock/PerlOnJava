package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.operators.ReferenceOperators;
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
        try {
            return operation.execute();
        } catch (SQLException e) {
            setError(handle, e);
        } catch (Exception e) {
            setError(handle, new SQLException(e.getMessage(), GENERAL_ERROR_STATE, DBI_ERROR_CODE));
        }
        RuntimeScalar msg = new RuntimeScalar("DBI " + methodName + "() failed: " + getGlobalVariable("DBI::errstr"));
        if (handle.get("RaiseError").getBoolean()) {
            Carp.croak(new RuntimeArray(msg), RuntimeContextType.VOID);
        }
        if (handle.get("PrintError").getBoolean()) {
            Carp.carp(new RuntimeArray(msg), RuntimeContextType.VOID);
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
            ResultSetMetaData metaData = stmt.getMetaData();
            int numFields = (metaData != null) ? metaData.getColumnCount() : 0;
            sth.put("NUM_OF_FIELDS", new RuntimeScalar(numFields));

            // Add NUM_OF_PARAMS by getting parameter count
            ParameterMetaData paramMetaData = stmt.getParameterMetaData();
            int numParams = paramMetaData.getParameterCount();
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

        return executeWithErrorHandling(() -> {
            if (args.isEmpty()) {
                throw new IllegalStateException("Bad number of arguments for DBI->execute");
            }

            // Get prepared statement from statement handle
            PreparedStatement stmt = (PreparedStatement) sth.get("statement").value;

            // Bind parameters to prepared statement if provided
            for (int i = 1; i < args.size(); i++) {
                stmt.setObject(i, args.get(i).value);
            }

            // Execute the statement and check for result set
            boolean hasResultSet = stmt.execute();

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
            return result.createReference().getList();
        }, dbh, "execute");
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
                // Convert each column value to string and add to row array
                for (int i = 1; i <= metaData.getColumnCount(); i++) {
                    RuntimeArray.push(row, RuntimeScalar.newScalarOrString(rs.getObject(i)));
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
    private static void setError(RuntimeHash handle, SQLException exception) {
        if (exception != null) {
            handle.put("err", new RuntimeScalar(exception.getErrorCode()));
            handle.put("errstr", new RuntimeScalar(exception.getMessage()));
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

            PreparedStatement stmt = (PreparedStatement) sth.get("statement").value;
            int paramIndex = args.get(1).getInt();
            Object value = args.get(2).value;

            // Store bound parameters for later use
            RuntimeHash boundParams = sth.get("bound_params") != null ?
                    sth.get("bound_params").hashDeref() : new RuntimeHash();
            boundParams.put(String.valueOf(paramIndex), new RuntimeScalar(value));
            sth.put("bound_params", boundParams.createReference());

            stmt.setObject(paramIndex, value);
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

            PreparedStatement stmt = (PreparedStatement) sth.get("statement").value;
            int paramIndex = args.get(1).getInt();
            RuntimeScalar valueRef = args.get(2);

            // Store bound parameters for later use
            RuntimeHash boundParams = sth.get("bound_params") != null ?
                    sth.get("bound_params").hashDeref() : new RuntimeHash();
            boundParams.put(String.valueOf(paramIndex), valueRef);
            sth.put("bound_params", boundParams.createReference());

            stmt.setObject(paramIndex, valueRef.value);
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
            return sth.createReference().getList();
        }, dbh, "table_info");
    }

    public static RuntimeList column_info(RuntimeArray args, int ctx) {
        RuntimeHash dbh = args.get(0).hashDeref();

        return executeWithErrorHandling(() -> {
            if (args.size() < 4) {
                throw new IllegalStateException("Bad number of arguments for DBI->column_info");
            }

            Connection conn = (Connection) dbh.get("connection").value;
            DatabaseMetaData metaData = conn.getMetaData();

            String catalog = args.get(1).toString();
            String schema = args.get(2).toString();
            String table = args.get(3).toString();
            String column = args.size() > 4 ? args.get(4).toString() : "%";

            ResultSet rs = metaData.getColumns(catalog, schema, table, column);

            RuntimeHash sth = createMetadataResultSet(dbh, rs);
            return sth.createReference().getList();
        }, dbh, "column_info");
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
            return sth.createReference().getList();
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
            return sth.createReference().getList();
        }, dbh, "foreign_key_info");
    }

    public static RuntimeList type_info(RuntimeArray args, int ctx) {
        RuntimeHash dbh = args.get(0).hashDeref();

        return executeWithErrorHandling(() -> {
            Connection conn = (Connection) dbh.get("connection").value;
            DatabaseMetaData metaData = conn.getMetaData();
            ResultSet rs = metaData.getTypeInfo();

            RuntimeHash sth = createMetadataResultSet(dbh, rs);
            return sth.createReference().getList();
        }, dbh, "type_info");
    }

    private static RuntimeHash createMetadataResultSet(RuntimeHash dbh, ResultSet rs) throws SQLException {
        RuntimeHash sth = new RuntimeHash();
        sth.put("Database", dbh.createReference());

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

        return executeWithErrorHandling(() -> {
            RuntimeHash info = new RuntimeHash();
            Connection conn = (Connection) dbh.get("connection").value;
            DatabaseMetaData meta = conn.getMetaData();

            // Add standard database information using available JDBC methods
            info.put("DBMS_NAME", RuntimeScalar.newScalarOrString(meta.getDatabaseProductName()));
            info.put("DBMS_VERSION", RuntimeScalar.newScalarOrString(meta.getDatabaseProductVersion()));
            info.put("DRIVER_NAME", RuntimeScalar.newScalarOrString(meta.getDriverName()));
            info.put("DRIVER_VERSION", RuntimeScalar.newScalarOrString(meta.getDriverVersion()));
            info.put("IDENTIFIER_QUOTE_CHAR", RuntimeScalar.newScalarOrString(meta.getIdentifierQuoteString()));
            info.put("SQL_KEYWORDS", RuntimeScalar.newScalarOrString(meta.getSQLKeywords()));
            info.put("MAX_CONNECTIONS", RuntimeScalar.newScalarOrString(meta.getMaxConnections()));
            info.put("USER_NAME", RuntimeScalar.newScalarOrString(meta.getUserName()));
            info.put("NUMERIC_FUNCTIONS", RuntimeScalar.newScalarOrString(meta.getNumericFunctions()));
            info.put("STRING_FUNCTIONS", RuntimeScalar.newScalarOrString(meta.getStringFunctions()));

            return info.createReference().getList();
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