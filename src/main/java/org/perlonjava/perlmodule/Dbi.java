package org.perlonjava.perlmodule;

import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeHash;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

import javax.sql.DataSource;
import java.sql.*;
import java.util.Properties;

/**
 * DBI (Database Independent Interface) module implementation for PerlonJava.
 * This class provides database connectivity and operations similar to Perl's DBI module.
 */
public class Dbi extends PerlModuleBase {

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
            dbi.registerMethod("fetchrow_array", null);
            dbi.registerMethod("rows", null);
            dbi.registerMethod("disconnect", null);
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
     * @param ctx Context parameter
     * @return RuntimeList containing database handle (dbh)
     * @throws Exception if connection fails or arguments are invalid
     */
    public static RuntimeList connect(RuntimeArray args, int ctx) throws Exception {
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
        RuntimeHash dbh = new RuntimeHash();
        dbh.put("connection", new RuntimeScalar(conn));
        dbh.put("active", new RuntimeScalar(true));

        // Create blessed reference for Perl compatibility
        RuntimeScalar dbhRef = RuntimeScalar.bless(dbh.createReference(), new RuntimeScalar("DBI"));
        return dbhRef.getList();
    }

    /**
     * Prepares an SQL statement for execution.
     *
     * @param args RuntimeArray containing:
     *             [0] - Database handle (dbh)
     *             [1] - SQL query string
     * @param ctx Context parameter
     * @return RuntimeList containing statement handle (sth)
     * @throws Exception if preparation fails or arguments are invalid
     */
    public static RuntimeList prepare(RuntimeArray args, int ctx) throws Exception {
        if (args.size() < 2) {
            throw new IllegalStateException("Bad number of arguments for DBI->prepare");
        }

        // Extract database handle and SQL query
        RuntimeHash dbh = args.get(0).hashDeref();
        String sql = args.get(1).toString();

        // Get connection from database handle and prepare statement
        Connection conn = (Connection) dbh.get("connection").value;
        PreparedStatement stmt = conn.prepareStatement(sql);

        // Create statement handle (sth) hash
        RuntimeHash sth = new RuntimeHash();
        sth.put("statement", new RuntimeScalar(stmt));
        sth.put("sql", new RuntimeScalar(sql));

        // Create blessed reference for statement handle
        RuntimeScalar sthRef = RuntimeScalar.bless(sth.createReference(), new RuntimeScalar("DBI"));
        return sthRef.getList();
    }

    /**
     * Executes a prepared statement with optional parameters.
     *
     * @param args RuntimeArray containing:
     *             [0] - Statement handle (sth)
     *             [1..n] - Optional bind parameters
     * @param ctx Context parameter
     * @return RuntimeList containing execution result
     * @throws Exception if execution fails
     */
    public static RuntimeList execute(RuntimeArray args, int ctx) throws Exception {
        if (args.size() < 1) {
            throw new IllegalStateException("Bad number of arguments for DBI->execute");
        }

        // Get prepared statement from statement handle
        RuntimeHash sth = args.get(0).hashDeref();
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
    }

    /**
     * Fetches the next row from a result set as an array.
     *
     * @param args RuntimeArray containing statement handle (sth)
     * @param ctx Context parameter
     * @return RuntimeList containing row data or empty list if no more rows
     * @throws Exception if fetch operation fails
     */
    public static RuntimeList fetchrow_array(RuntimeArray args, int ctx) throws Exception {
        // Get statement handle and result set
        RuntimeHash sth = args.get(0).hashDeref();
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
            return row.getList();
        }

        // Return empty array if no more rows
        return new RuntimeArray().getList();
    }

    /**
     * Returns the number of rows affected by the last statement execution.
     *
     * @param args RuntimeArray containing statement handle (sth)
     * @param ctx Context parameter
     * @return RuntimeList containing row count (-1 if not available)
     * @throws Exception if row count operation fails
     */
    public static RuntimeList rows(RuntimeArray args, int ctx) throws Exception {
        if (args.size() < 1) {
            throw new IllegalStateException("Bad number of arguments for DBI->rows");
        }

        // Get statement handle
        RuntimeHash sth = args.get(0).hashDeref();
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
    }

    /**
     * Closes the database connection and marks it as inactive.
     *
     * @param args RuntimeArray containing database handle (dbh)
     * @param ctx Context parameter
     * @return RuntimeList containing empty hash reference
     * @throws Exception if disconnection fails
     */
    public static RuntimeList disconnect(RuntimeArray args, int ctx) throws Exception {
        // Get database handle and close connection
        RuntimeHash dbh = args.get(0).hashDeref();
        Connection conn = (Connection) dbh.get("connection").value;
        conn.close();

        // Mark connection as inactive
        dbh.put("active", new RuntimeScalar(false));

        return new RuntimeHash().createReference().getList();
    }
}
