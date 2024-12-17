package org.perlonjava.perlmodule;

import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeHash;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

import java.sql.*;
import java.util.Arrays;

import static org.perlonjava.runtime.GlobalVariable.getGlobalVariable;
import static org.perlonjava.runtime.RuntimeScalarCache.scalarUndef;

/**
 * DBI (Database Independent Interface) module implementation for PerlonJava.
 * This class provides database connectivity and operations similar to Perl's DBI module.
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
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing DBI method: " + e.getMessage());
        }
    }

    /**
     * Establishes a database connection using the provided credentials.
     *
     * @param args RuntimeArray containing connection parameters:
     *             [0] - DBI object
     *             [1] - DSN (Data Source Name) in format "dbi:Driver:database:host"
     *             [2] - Username
     *             [3] - Password
     * @param ctx  Context parameter
     * @return RuntimeList containing database handle (dbh)
     */
    public static RuntimeList connect(RuntimeArray args, int ctx) {
        RuntimeHash dbh = new RuntimeHash();
        try {
            if (args.size() < 4) {
                throw new IllegalStateException("Bad number of arguments for DBI->connect");
            }

            // Extract connection parameters from args
            String dsn = args.get(1).toString();
            String username = args.get(2).toString();
            String password = args.get(3).toString();

            // Split DSN into components (format: dbi:Driver:database:host)
            String[] dsnParts = dsn.split(":");
            String driverClass = dsnParts[1];

            // Dynamically load the JDBC driver
            Class.forName(driverClass);

            // Extract database protocol from driver class name
            String protocol = driverClass.split("\\.")[1].toLowerCase();

            // Construct JDBC URL from DSN components
            String jdbcUrl = "jdbc:" + protocol + ":" + dsnParts[2] + ":" + dsnParts[3];

            // Establish database connection
            Connection conn = DriverManager.getConnection(jdbcUrl, username, password);

            // Create database handle (dbh) hash and store connection
            dbh.put("connection", new RuntimeScalar(conn));
            dbh.put("active", new RuntimeScalar(true));

            // Create blessed reference for Perl compatibility
            RuntimeScalar dbhRef = RuntimeScalar.bless(dbh.createReference(), new RuntimeScalar("DBI"));
            return dbhRef.getList();
        } catch (SQLException e) {
            setError(dbh, e);
            return scalarUndef.getList();
        } catch (ClassNotFoundException e) {
            setError(dbh, new SQLException("Database driver not found: " + e.getMessage(), GENERAL_ERROR_STATE, DBI_ERROR_CODE));
            return scalarUndef.getList();
        } catch (Exception e) {
            setError(dbh, new SQLException(e.getMessage(), GENERAL_ERROR_STATE, DBI_ERROR_CODE));
            return scalarUndef.getList();
        }
    }

    /**
     * Prepares an SQL statement for execution.
     *
     * @param args RuntimeArray containing:
     *             [0] - Database handle (dbh)
     *             [1] - SQL query string
     * @param ctx  Context parameter
     * @return RuntimeList containing statement handle (sth)
     */
    public static RuntimeList prepare(RuntimeArray args, int ctx) {
        RuntimeHash dbh = args.get(0).hashDeref();
        RuntimeHash sth = new RuntimeHash();
        try {
            if (args.size() < 2) {
                throw new IllegalStateException("Bad number of arguments for DBI->prepare");
            }

            // Extract database handle and SQL query
            String sql = args.get(1).toString();

            // Get connection from database handle and prepare statement
            Connection conn = (Connection) dbh.get("connection").value;
            PreparedStatement stmt = conn.prepareStatement(sql);

            // Create statement handle (sth) hash
            sth.put("statement", new RuntimeScalar(stmt));
            sth.put("sql", new RuntimeScalar(sql));

            // Create blessed reference for statement handle
            RuntimeScalar sthRef = RuntimeScalar.bless(sth.createReference(), new RuntimeScalar("DBI"));
            return sthRef.getList();
        } catch (SQLException e) {
            setError(sth, e);
            return new RuntimeHash().createReference().getList();
        } catch (Exception e) {
            setError(sth, new SQLException(e.getMessage(), GENERAL_ERROR_STATE, DBI_ERROR_CODE));
            return new RuntimeHash().createReference().getList();
        }
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

            // Store result set if available
            if (hasResultSet) {
                result.put("resultset", new RuntimeScalar(stmt.getResultSet()));
            }

            // Store execution result in statement handle
            sth.put("execute_result", result.createReference());
            return result.createReference().getList();
        } catch (SQLException e) {
            setError(sth, e);
            return new RuntimeHash().createReference().getList();
        } catch (Exception e) {
            setError(sth, new SQLException(e.getMessage(), GENERAL_ERROR_STATE, DBI_ERROR_CODE));
            return new RuntimeHash().createReference().getList();
        }
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
        try {
            RuntimeHash executeResult = sth.get("execute_result").hashDeref();
            ResultSet rs = (ResultSet) executeResult.get("resultset").value;

            // Fetch next row if available
            if (rs.next()) {
                RuntimeArray row = new RuntimeArray();
                ResultSetMetaData metaData = rs.getMetaData();
                // Convert each column value to string and add to row array
                for (int i = 1; i <= metaData.getColumnCount(); i++) {
                    RuntimeArray.push(row, new RuntimeScalar(rs.getObject(i).toString()));
                }
                return row.createReference().getList();
            }

            // Return empty array if no more rows
            return new RuntimeList();
        } catch (SQLException e) {
            setError(sth, e);
            return new RuntimeList();
        } catch (Exception e) {
            setError(sth, new SQLException(e.getMessage(), GENERAL_ERROR_STATE, DBI_ERROR_CODE));
            return new RuntimeList();
        }
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
        try {
            RuntimeHash executeResult = sth.get("execute_result").hashDeref();
            ResultSet rs = (ResultSet) executeResult.get("resultset").value;

            // Fetch next row if available
            if (rs.next()) {
                RuntimeHash row = new RuntimeHash();
                ResultSetMetaData metaData = rs.getMetaData();

                // For each column, add column name -> value pair to hash
                for (int i = 1; i <= metaData.getColumnCount(); i++) {
                    String columnName = metaData.getColumnName(i);
                    Object value = rs.getObject(i);
                    row.put(columnName, new RuntimeScalar(value != null ? value.toString() : ""));
                }

                // Create reference for hash
                RuntimeScalar rowRef = row.createReference();
                return rowRef.getList();
            }

            // Return undef if no more rows
            return scalarUndef.getList();
        } catch (SQLException e) {
            setError(sth, e);
            return scalarUndef.getList();
        } catch (Exception e) {
            setError(sth, new SQLException(e.getMessage(), GENERAL_ERROR_STATE, DBI_ERROR_CODE));
            return scalarUndef.getList();
        }
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
            dbh.put("active", new RuntimeScalar(false));

            return new RuntimeHash().createReference().getList();
        } catch (SQLException e) {
            setError(dbh, e);
            return new RuntimeHash().createReference().getList();
        } catch (Exception e) {
            setError(dbh, new SQLException(e.getMessage(), GENERAL_ERROR_STATE, DBI_ERROR_CODE));
            return new RuntimeHash().createReference().getList();
        }
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
}
