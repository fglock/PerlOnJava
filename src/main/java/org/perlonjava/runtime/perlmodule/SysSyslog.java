package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Java XS implementation for Sys::Syslog.
 *
 * Sys::Syslog's Perl module implements most argument validation and fallback
 * transport logic itself. The XS layer supplies constants plus thin wrappers
 * around the platform syslog functions. PerlOnJava does not call libc syslog,
 * so the native transport is implemented as a successful no-op.
 */
public class SysSyslog extends PerlModuleBase {
    public static final String XS_VERSION = "0.36";

    private static final int LOG_EMERG = 0;
    private static final int LOG_ALERT = 1;
    private static final int LOG_CRIT = 2;
    private static final int LOG_ERR = 3;
    private static final int LOG_WARNING = 4;
    private static final int LOG_NOTICE = 5;
    private static final int LOG_INFO = 6;
    private static final int LOG_DEBUG = 7;

    private static final int LOG_KERN = 0 << 3;
    private static final int LOG_USER = 1 << 3;
    private static final int LOG_MAIL = 2 << 3;
    private static final int LOG_DAEMON = 3 << 3;
    private static final int LOG_AUTH = 4 << 3;
    private static final int LOG_SYSLOG = 5 << 3;
    private static final int LOG_LPR = 6 << 3;
    private static final int LOG_NEWS = 7 << 3;
    private static final int LOG_UUCP = 8 << 3;
    private static final int LOG_CRON = 9 << 3;
    private static final int LOG_AUTHPRIV = 10 << 3;
    private static final int LOG_FTP = 11 << 3;
    private static final int LOG_LOCAL0 = 16 << 3;
    private static final int LOG_LOCAL1 = 17 << 3;
    private static final int LOG_LOCAL2 = 18 << 3;
    private static final int LOG_LOCAL3 = 19 << 3;
    private static final int LOG_LOCAL4 = 20 << 3;
    private static final int LOG_LOCAL5 = 21 << 3;
    private static final int LOG_LOCAL6 = 22 << 3;
    private static final int LOG_LOCAL7 = 23 << 3;

    private static final int LOG_PID = 0x01;
    private static final int LOG_CONS = 0x02;
    private static final int LOG_ODELAY = 0x04;
    private static final int LOG_NDELAY = 0x08;
    private static final int LOG_NOWAIT = 0x10;
    private static final int LOG_PERROR = 0x20;

    private static final int LOG_PRIMASK = 0x07;
    private static final int LOG_FACMASK = 0x03f8;

    private static final Map<String, Object> CONSTANTS = new HashMap<>();

    private static String ident = "";
    private static int logOptions = 0;
    private static int currentMask = logUpto(LOG_DEBUG);

    static {
        CONSTANTS.put("LOG_EMERG", LOG_EMERG);
        CONSTANTS.put("LOG_ALERT", LOG_ALERT);
        CONSTANTS.put("LOG_CRIT", LOG_CRIT);
        CONSTANTS.put("LOG_ERR", LOG_ERR);
        CONSTANTS.put("LOG_WARNING", LOG_WARNING);
        CONSTANTS.put("LOG_NOTICE", LOG_NOTICE);
        CONSTANTS.put("LOG_INFO", LOG_INFO);
        CONSTANTS.put("LOG_DEBUG", LOG_DEBUG);

        CONSTANTS.put("LOG_KERN", LOG_KERN);
        CONSTANTS.put("LOG_USER", LOG_USER);
        CONSTANTS.put("LOG_MAIL", LOG_MAIL);
        CONSTANTS.put("LOG_DAEMON", LOG_DAEMON);
        CONSTANTS.put("LOG_AUTH", LOG_AUTH);
        CONSTANTS.put("LOG_AUTHPRIV", LOG_AUTHPRIV);
        CONSTANTS.put("LOG_CRON", LOG_CRON);
        CONSTANTS.put("LOG_FTP", LOG_FTP);
        CONSTANTS.put("LOG_LPR", LOG_LPR);
        CONSTANTS.put("LOG_NEWS", LOG_NEWS);
        CONSTANTS.put("LOG_SYSLOG", LOG_SYSLOG);
        CONSTANTS.put("LOG_UUCP", LOG_UUCP);
        CONSTANTS.put("LOG_LOCAL0", LOG_LOCAL0);
        CONSTANTS.put("LOG_LOCAL1", LOG_LOCAL1);
        CONSTANTS.put("LOG_LOCAL2", LOG_LOCAL2);
        CONSTANTS.put("LOG_LOCAL3", LOG_LOCAL3);
        CONSTANTS.put("LOG_LOCAL4", LOG_LOCAL4);
        CONSTANTS.put("LOG_LOCAL5", LOG_LOCAL5);
        CONSTANTS.put("LOG_LOCAL6", LOG_LOCAL6);
        CONSTANTS.put("LOG_LOCAL7", LOG_LOCAL7);

        // Platform-specific facilities with the same fallbacks as Sys::Syslog's Makefile.PL.
        CONSTANTS.put("LOG_INSTALL", LOG_USER);
        CONSTANTS.put("LOG_LAUNCHD", LOG_DAEMON);
        CONSTANTS.put("LOG_NETINFO", LOG_DAEMON);
        CONSTANTS.put("LOG_RAS", LOG_AUTH);
        CONSTANTS.put("LOG_REMOTEAUTH", LOG_AUTH);
        CONSTANTS.put("LOG_CONSOLE", LOG_USER);
        CONSTANTS.put("LOG_NTP", LOG_DAEMON);
        CONSTANTS.put("LOG_SECURITY", LOG_AUTH);
        CONSTANTS.put("LOG_AUDIT", LOG_AUTH);
        CONSTANTS.put("LOG_LFMT", LOG_USER);

        CONSTANTS.put("LOG_CONS", LOG_CONS);
        CONSTANTS.put("LOG_PID", LOG_PID);
        CONSTANTS.put("LOG_NDELAY", LOG_NDELAY);
        CONSTANTS.put("LOG_NOWAIT", LOG_NOWAIT);
        CONSTANTS.put("LOG_ODELAY", LOG_ODELAY);
        CONSTANTS.put("LOG_PERROR", LOG_PERROR);

        CONSTANTS.put("LOG_FACMASK", LOG_FACMASK);
        CONSTANTS.put("LOG_PRIMASK", LOG_PRIMASK);
        CONSTANTS.put("LOG_NFACILITIES", 30);

        // Deliberately empty: native no-op logging is available, and this avoids
        // misdetecting a Unix socket transport that PerlOnJava may not support.
        CONSTANTS.put("_PATH_LOG", "");
    }

    public SysSyslog() {
        super("Sys::Syslog", false);
    }

    public static void initialize() {
        SysSyslog module = new SysSyslog();
        try {
            module.registerMethod("constant", null);
            module.registerMethod("LOG_FAC", null);
            module.registerMethod("LOG_PRI", null);
            module.registerMethod("LOG_MAKEPRI", null);
            module.registerMethod("LOG_MASK", null);
            module.registerMethod("LOG_UPTO", null);
            module.registerMethod("openlog_xs", null);
            module.registerMethod("syslog_xs", null);
            module.registerMethod("setlogmask_xs", null);
            module.registerMethod("closelog_xs", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing Sys::Syslog method: " + e.getMessage());
        }

        GlobalVariable.getGlobalVariable("Sys::Syslog::XS_VERSION").set(new RuntimeScalar(XS_VERSION));
        registerConstantSubs();
    }

    private static void registerConstantSubs() {
        for (Map.Entry<String, Object> entry : CONSTANTS.entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            PerlSubroutine sub = (args, ctx) -> scalarFor(value).getList();
            RuntimeCode code = new RuntimeCode(sub, "");
            code.isStatic = true;
            code.packageName = "Sys::Syslog";
            code.subName = name;
            String fullName = NameNormalizer.normalizeVariableName(name, "Sys::Syslog");
            GlobalVariable.getGlobalCodeRef(fullName).set(new RuntimeScalar(code));
        }
    }

    private static RuntimeScalar scalarFor(Object value) {
        if (value instanceof String string) {
            return new RuntimeScalar(string);
        }
        return new RuntimeScalar(((Number) value).longValue());
    }

    public static RuntimeList constant(RuntimeArray args, int ctx) {
        String name = args.size() > 0 ? args.get(0).toString() : "";
        Object value = CONSTANTS.get(name);
        if (value == null) {
            return new RuntimeScalar(name + " is not a valid Sys::Syslog macro").getList();
        }
        RuntimeList result = new RuntimeList();
        result.add(new RuntimeScalar());
        result.add(scalarFor(value));
        return result;
    }

    public static RuntimeList LOG_FAC(RuntimeArray args, int ctx) {
        int priority = args.size() > 0 ? args.get(0).getInt() : 0;
        return new RuntimeScalar((priority & LOG_FACMASK) >> 3).getList();
    }

    public static RuntimeList LOG_PRI(RuntimeArray args, int ctx) {
        int priority = args.size() > 0 ? args.get(0).getInt() : 0;
        return new RuntimeScalar(priority & LOG_PRIMASK).getList();
    }

    public static RuntimeList LOG_MAKEPRI(RuntimeArray args, int ctx) {
        int facility = args.size() > 0 ? args.get(0).getInt() : 0;
        int priority = args.size() > 1 ? args.get(1).getInt() : 0;
        return new RuntimeScalar((facility << 3) | priority).getList();
    }

    public static RuntimeList LOG_MASK(RuntimeArray args, int ctx) {
        int priority = args.size() > 0 ? args.get(0).getInt() : 0;
        return new RuntimeScalar(1 << priority).getList();
    }

    public static RuntimeList LOG_UPTO(RuntimeArray args, int ctx) {
        int priority = args.size() > 0 ? args.get(0).getInt() : 0;
        return new RuntimeScalar(logUpto(priority)).getList();
    }

    public static RuntimeList openlog_xs(RuntimeArray args, int ctx) {
        ident = args.size() > 0 ? args.get(0).toString() : "";
        logOptions = args.size() > 1 ? args.get(1).getInt() : 0;
        return new RuntimeList();
    }

    public static RuntimeList syslog_xs(RuntimeArray args, int ctx) {
        if ((logOptions & LOG_PERROR) != 0) {
            String message = args.size() > 1 ? args.get(1).toString() : "";
            String prefix = ident == null || ident.isEmpty() ? "syslog" : ident;
            if ((logOptions & LOG_PID) != 0) {
                prefix += "[" + ProcessHandle.current().pid() + "]";
            }
            System.err.print(prefix + ": " + message);
            if (!message.endsWith("\n")) {
                System.err.println();
            }
        }
        return new RuntimeList();
    }

    public static RuntimeList setlogmask_xs(RuntimeArray args, int ctx) {
        int oldMask = currentMask;
        if (args.size() > 0) {
            currentMask = args.get(0).getInt();
        }
        return new RuntimeScalar(oldMask).getList();
    }

    public static RuntimeList closelog_xs(RuntimeArray args, int ctx) {
        ident = "";
        logOptions = 0;
        return new RuntimeList();
    }

    private static int logUpto(int priority) {
        return (1 << (priority + 1)) - 1;
    }
}
