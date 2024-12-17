package org.perlonjava.perlmodule;

import org.perlonjava.runtime.RuntimeArray;
import org.perlonjava.runtime.RuntimeHash;
import org.perlonjava.runtime.RuntimeList;
import org.perlonjava.runtime.RuntimeScalar;

import javax.sql.DataSource;
import java.sql.*;
import java.util.Properties;

public class Dbi extends PerlModuleBase {

    public Dbi() {
        super("DBI", false);
    }

    public static void initialize() {
        Dbi dbi = new Dbi();
        try {
            dbi.registerMethod("connect", null);
            dbi.registerMethod("prepare", null);
            dbi.registerMethod("execute", null);
            dbi.registerMethod("fetchrow_array", null);
            dbi.registerMethod("disconnect", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing DBI method: " + e.getMessage());
        }
    }

    public static RuntimeList connect(RuntimeArray args, int ctx) throws Exception {
        if (args.size() < 4) {
            throw new IllegalStateException("Bad number of arguments for DBI->connect");
        }

        String dsn = args.get(1).toString();
        String username = args.get(2).toString();
        String password = args.get(3).toString();

        // Parse DSN
        String[] dsnParts = dsn.split(":");
        String driverClass = dsnParts[1];

        // Load the driver directly from DSN
        Class.forName(driverClass);

        // Extract protocol from driver class name
        String protocol = driverClass.split("\\.")[1].toLowerCase();

        // Preserve the full connection string including mem: prefix
        String jdbcUrl = "jdbc:" + protocol + ":" + dsnParts[2] + ":" + dsnParts[3];

        Connection conn = DriverManager.getConnection(jdbcUrl, username, password);

        RuntimeHash dbh = new RuntimeHash();
        dbh.put("connection", new RuntimeScalar(conn));
        dbh.put("active", new RuntimeScalar(true));

        // XXX TODO return "dbh" class
        // RuntimeScalar dbhRef = RuntimeScalar.bless(dbh.createReference(), new RuntimeScalar("DBI::db"));
        RuntimeScalar dbhRef = RuntimeScalar.bless(dbh.createReference(), new RuntimeScalar("DBI"));
        return dbhRef.getList();
    }

    public static RuntimeList prepare(RuntimeArray args, int ctx) throws Exception {
        if (args.size() < 2) {
            throw new IllegalStateException("Bad number of arguments for DBI->prepare");
        }

        RuntimeHash dbh = args.get(0).hashDeref();
        String sql = args.get(1).toString();

        Connection conn = (Connection) dbh.get("connection").value;
        PreparedStatement stmt = conn.prepareStatement(sql);

        RuntimeHash sth = new RuntimeHash();
        sth.put("statement", new RuntimeScalar(stmt));
        sth.put("sql", new RuntimeScalar(sql));

        // XXX TODO return "sth" class
        RuntimeScalar sthRef = RuntimeScalar.bless(sth.createReference(), new RuntimeScalar("DBI"));
        return sthRef.getList();
    }

    public static RuntimeList execute(RuntimeArray args, int ctx) throws Exception {
        if (args.size() < 1) {
            throw new IllegalStateException("Bad number of arguments for DBI->execute");
        }

        RuntimeHash sth = args.get(0).hashDeref();
        PreparedStatement stmt = (PreparedStatement) sth.get("statement").value;

        // Bind parameters if provided
        for (int i = 1; i < args.size(); i++) {
            stmt.setObject(i, args.get(i).value);
        }

        boolean hasResultSet = stmt.execute();
        RuntimeHash result = new RuntimeHash();
        result.put("success", new RuntimeScalar(true));
        result.put("has_resultset", new RuntimeScalar(hasResultSet));

        if (hasResultSet) {
            result.put("resultset", new RuntimeScalar(stmt.getResultSet()));
        }

        return result.createReference().getList();
    }

    public static RuntimeList fetchrow_array(RuntimeArray args, int ctx) throws Exception {
        RuntimeHash sth = args.get(0).hashDeref();
        ResultSet rs = (ResultSet) sth.get("resultset").value;

        if (rs.next()) {
            RuntimeArray row = new RuntimeArray();
            ResultSetMetaData metaData = rs.getMetaData();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                RuntimeArray.push(row, new RuntimeScalar(rs.getObject(i)));
            }
            return row.getList();
        }

        return new RuntimeArray().getList();
    }

    public static RuntimeList disconnect(RuntimeArray args, int ctx) throws Exception {
        RuntimeHash sth = args.get(0).hashDeref();
        PreparedStatement stmt = (PreparedStatement) sth.get("statement").value;
        stmt.close();

        return new RuntimeHash().createReference().getList();
    }
}
