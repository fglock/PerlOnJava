package org.perlonjava.perlmodule;

import org.perlonjava.runtime.*;

import java.sql.*;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeScalarCache.*;

/**
 * DBI (Database Independent Interface) module implementation for PerlonJava.
 * This class provides database connectivity and operations similar to Perl's DBI module.
 * <p>
 * Note: Some methods are defined in src/main/perl/lib/DBI.pm
 */
public class Dbi extends PerlModuleBase {

    private static final int DBI_ERROR_CODE = 2000000000;  // Default $DBI::stderr value
    private static final String GENERAL_ERROR_STATE = "S1000";

    /**
     * Constructor initializes the DBI module.
     */
    public Dbi() {
        super("DBI", false);
    }

    /**
     * Initializes and registers all DBI methods.
     * This method must be called before using any DBI functionality.
     */
    public static void initialize() {
        // Create new DBI instance
        Dbi dbi = new Dbi();
        try {
            // Register all supported DBI methods
            dbi.registerMethod("connect", null);
            dbi.registerMethod("prepare", null);
            dbi.registerMethod("execute", null);
            dbi.registerMethod("fetchrow_arrayref", null);
            dbi.registerMethod("fetchrow_hashref", null);
            dbi.registerMethod("rows", null);
            dbi.registerMethod("disconnect", null);
            dbi.registerMethod("err", null);
            dbi.registerMethod("errstr", null);
            dbi.registerMethod("state", null);
            dbi.registerMethod("last_insert_id", null);
            dbi.registerMethod("begin_work", null);
            dbi.registerMethod("commit", null);
            dbi.registerMethod("rollback", null);        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing DBI method: " + e.getMessage());
        }
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
        String jdbcUrl = null;
        try {
            if (args.size() < 4) {
                throw new IllegalStateException("Bad number of arguments for DBI->connect");
            }

            // Extract connection parameters from args
            jdbcUrl = args.get(1).toString();
            dbh.put("Username", new RuntimeScalar(args.get(2).toString()));
            dbh.put("Password", new RuntimeScalar(args.get(3).toString()));
            RuntimeScalar attr = args.get(4);   //  \%attr

            // Set dbh attributes
            dbh.put("ReadOnly", scalarFalse);
            dbh.put("AutoCommit", scalarTrue);
            if (attr.type == RuntimeScalarType.HASHREFERENCE) {
                // add attributes to dbh: RaiseError, PrintError, FetchHashKeyName
                dbh.setFromList(new RuntimeList(dbh, attr.hashDeref()));
            }

            // Establish database connection
            Connection conn = DriverManager.getConnection(jdbcUrl, dbh.get("Username").toString(), dbh.get("Password").toString());

            // Remove password from dbh hash
            dbh.delete(new RuntimeScalar("Password"));

            // Create database handle (dbh) hash and store connection
            dbh.put("connection", new RuntimeScalar(conn));
            dbh.put("Active", new RuntimeScalar(true));
            dbh.put("Type", new RuntimeScalar("db"));
            dbh.put("Name", new RuntimeScalar(jdbcUrl));

            // Create blessed reference for Perl compatibility
            RuntimeScalar dbhRef = RuntimeScalar.bless(dbh.createReference(), new RuntimeScalar("DBI"));
            return dbhRef.getList();
        } catch (SQLException e) {
            setError(dbh, e);
        } catch (Exception e) {
            setError(dbh, new SQLException(e.getMessage(), GENERAL_ERROR_STATE, DBI_ERROR_CODE));
        }
        RuntimeScalar msg = new RuntimeScalar("DBI connect('" + jdbcUrl + "','" + dbh.get("Username") + "',...) failed: " + getGlobalVariable("DBI::errstr"));
        return handleError(dbh, msg);
    }

    private static RuntimeList handleError(RuntimeHash dbh, RuntimeScalar msg) {
        if (dbh.get("RaiseError").getBoolean()) {
            Carp.croak(new RuntimeArray(msg), RuntimeContextType.VOID);
        }
        if (dbh.get("PrintError").getBoolean()) {
            Carp.carp(new RuntimeArray(msg), RuntimeContextType.VOID);
        }
        return new RuntimeList();
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
        try {
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
            RuntimeScalar sthRef = RuntimeScalar.bless(sth.createReference(), new RuntimeScalar("DBI"));

            dbh.get("sth").set(sthRef);

            return sthRef.getList();
        } catch (SQLException e) {
            setError(sth, e);
        } catch (Exception e) {
            setError(sth, new SQLException(e.getMessage(), GENERAL_ERROR_STATE, DBI_ERROR_CODE));
        }
        RuntimeScalar msg = new RuntimeScalar("DBI prepare() failed: " + getGlobalVariable("DBI::errstr"));
        return handleError(dbh, msg);
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

        try {
            Connection conn = (Connection) dbh.get("connection").value;
            Statement stmt = (Statement) sth.get("statement").value;
            ResultSet rs = stmt.getGeneratedKeys();

            if (rs.next()) {
                long id = rs.getLong(1);
                return new RuntimeScalar(id).getList();
            }

            return scalarUndef.getList();
        } catch (SQLException e) {
            setError(dbh, e);
        } catch (Exception e) {
            setError(dbh, new SQLException(e.getMessage(), GENERAL_ERROR_STATE, DBI_ERROR_CODE));
        }
        RuntimeScalar msg = new RuntimeScalar("DBI last_insert_id() failed: " + getGlobalVariable("DBI::errstr"));
        return handleError(dbh, msg);
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
        try {
            if (args.size() < 1) {
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
        } catch (SQLException e) {
            setError(sth, e);
        } catch (Exception e) {
            setError(sth, new SQLException(e.getMessage(), GENERAL_ERROR_STATE, DBI_ERROR_CODE));
        }
        RuntimeScalar msg = new RuntimeScalar("DBI execute() failed: " + getGlobalVariable("DBI::errstr"));
        return handleError(dbh, msg);
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
        try {
            RuntimeHash executeResult = sth.get("execute_result").hashDeref();
            ResultSet rs = (ResultSet) executeResult.get("resultset").value;

            // Fetch next row if available
            if (rs.next()) {
                RuntimeArray row = new RuntimeArray();
                ResultSetMetaData metaData = rs.getMetaData();
                // Convert each column value to string and add to row array
                for (int i = 1; i <= metaData.getColumnCount(); i++) {
                    Object value = rs.getObject(i);
                    RuntimeArray.push(row, RuntimeScalar.newScalarOrString(value));
                }
                return row.createReference().getList();
            }

            // Return empty array if no more rows
            return new RuntimeList();
        } catch (SQLException e) {
            setError(sth, e);
        } catch (Exception e) {
            setError(sth, new SQLException(e.getMessage(), GENERAL_ERROR_STATE, DBI_ERROR_CODE));
        }
        RuntimeScalar msg = new RuntimeScalar("DBI fetchrow_arrayref() failed: " + getGlobalVariable("DBI::errstr"));
        return handleError(dbh, msg);
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
        try {
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
        } catch (SQLException e) {
            setError(sth, e);
        } catch (Exception e) {
            setError(sth, new SQLException(e.getMessage(), GENERAL_ERROR_STATE, DBI_ERROR_CODE));
        }
        RuntimeScalar msg = new RuntimeScalar("DBI fetchrow_hashref() failed: " + getGlobalVariable("DBI::errstr"));
        return handleError(dbh, msg);
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
        try {
            if (args.size() < 1) {
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
        } catch (Exception e) {
            setError(sth, new SQLException(e.getMessage(), GENERAL_ERROR_STATE, DBI_ERROR_CODE));
            return new RuntimeArray(new RuntimeScalar(-1)).getList();
        }
    }

    /**
     * Closes the database connection and marks it as inactive.
     *
     * @param args RuntimeArray containing database handle (dbh)
     * @param ctx  Context parameter
     * @return RuntimeList containing empty hash reference
     */
    public static RuntimeList disconnect(RuntimeArray args, int ctx) {
        // Get database handle and close connection
        RuntimeHash dbh = args.get(0).hashDeref();
        try {
            Connection conn = (Connection) dbh.get("connection").value;
            conn.close();

            // Mark connection as inactive
            dbh.put("Active", new RuntimeScalar(false));

            return new RuntimeHash().createReference().getList();
        } catch (SQLException e) {
            setError(dbh, e);
        } catch (Exception e) {
            setError(dbh, new SQLException(e.getMessage(), GENERAL_ERROR_STATE, DBI_ERROR_CODE));
        }
        RuntimeScalar msg = new RuntimeScalar("DBI disconnect() failed: " + getGlobalVariable("DBI::errstr"));
        return handleError(dbh, msg);
    }

    /**
     * Returns the native database engine error code from the last driver method called.
     *
     * @param args RuntimeArray containing handle (dbh or sth)
     * @param ctx  Context parameter
     * @return RuntimeList containing error code
     */
    public static RuntimeList err(RuntimeArray args, int ctx) {
        RuntimeHash handle = args.get(0).hashDeref();
        RuntimeScalar errorCode = handle.get("err");
        return new RuntimeArray(errorCode != null ? errorCode : scalarUndef).getList();
    }

    /**
     * Returns the native database engine error message from the last driver method called.
     *
     * @param args RuntimeArray containing handle (dbh or sth)
     * @param ctx  Context parameter
     * @return RuntimeList containing error message
     */
    public static RuntimeList errstr(RuntimeArray args, int ctx) {
        RuntimeHash handle = args.get(0).hashDeref();
        RuntimeScalar errorMessage = handle.get("errstr");
        return new RuntimeArray(errorMessage != null ? errorMessage : new RuntimeScalar("")).getList();
    }

    /**
     * Returns the SQLSTATE code for the last driver method called.
     *
     * @param args RuntimeArray containing handle (dbh or sth)
     * @param ctx  Context parameter
     * @return RuntimeList containing SQLSTATE code
     */
    public static RuntimeList state(RuntimeArray args, int ctx) {
        RuntimeHash handle = args.get(0).hashDeref();
        RuntimeScalar state = handle.get("state");
        // Return empty string for success code 00000
        if (state != null && "00000".equals(state.toString())) {
            return new RuntimeArray(new RuntimeScalar("")).getList();
        }
        return new RuntimeArray(state != null ? state : new RuntimeScalar(GENERAL_ERROR_STATE)).getList();
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
        try {
            Connection conn = (Connection) dbh.get("connection").value;
            conn.setAutoCommit(false);
            dbh.put("AutoCommit", scalarFalse);
            return scalarTrue.getList();
        } catch (SQLException e) {
            setError(dbh, e);
        } catch (Exception e) {
            setError(dbh, new SQLException(e.getMessage(), GENERAL_ERROR_STATE, DBI_ERROR_CODE));
        }
        RuntimeScalar msg = new RuntimeScalar("DBI begin_work() failed: " + getGlobalVariable("DBI::errstr"));
        return handleError(dbh, msg);
    }

    public static RuntimeList commit(RuntimeArray args, int ctx) {
        RuntimeHash dbh = args.get(0).hashDeref();
        try {
            Connection conn = (Connection) dbh.get("connection").value;
            conn.commit();
            conn.setAutoCommit(true);
            dbh.put("AutoCommit", scalarTrue);
            return scalarTrue.getList();
        } catch (SQLException e) {
            setError(dbh, e);
        } catch (Exception e) {
            setError(dbh, new SQLException(e.getMessage(), GENERAL_ERROR_STATE, DBI_ERROR_CODE));
        }
        RuntimeScalar msg = new RuntimeScalar("DBI commit() failed: " + getGlobalVariable("DBI::errstr"));
        return handleError(dbh, msg);
    }

    public static RuntimeList rollback(RuntimeArray args, int ctx) {
        RuntimeHash dbh = args.get(0).hashDeref();
        try {
            Connection conn = (Connection) dbh.get("connection").value;
            conn.rollback();
            conn.setAutoCommit(true);
            dbh.put("AutoCommit", scalarTrue);
            return scalarTrue.getList();
        } catch (SQLException e) {
            setError(dbh, e);
        } catch (Exception e) {
            setError(dbh, new SQLException(e.getMessage(), GENERAL_ERROR_STATE, DBI_ERROR_CODE));
        }
        RuntimeScalar msg = new RuntimeScalar("DBI rollback() failed: " + getGlobalVariable("DBI::errstr"));
        return handleError(dbh, msg);
    }
}

