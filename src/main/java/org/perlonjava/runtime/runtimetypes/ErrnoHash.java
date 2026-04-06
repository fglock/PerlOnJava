package org.perlonjava.runtime.runtimetypes;

import java.util.*;

/**
 * Implements the behavior of Perl's %! (errno hash).
 * <p>
 * In Perl, %! is a magic hash where each element has a non-zero value only if
 * $! is currently set to that errno value. For example:
 * <pre>
 *   $! = 2;   # ENOENT
 *   $!{ENOENT}  # returns 2 (true, because $! == ENOENT)
 *   $!{EPERM}   # returns 0 (false, because $! != EPERM)
 *   $!{NOSUCH}  # returns "" (constant doesn't exist)
 * </pre>
 * <p>
 * The exists() check tests whether the errno constant is known on this platform:
 * <pre>
 *   exists $!{ENOENT}  # true
 *   exists $!{NOSUCH}  # false
 * </pre>
 * <p>
 * This class extends AbstractMap to provide the hash-like interface,
 * following the same pattern as HashSpecialVariable for %+ and %-.
 * The errno constant tables are platform-specific (macOS/Darwin vs Linux),
 * matching the values defined in Errno.pm.
 */
public class ErrnoHash extends AbstractMap<String, RuntimeScalar> {

    // Platform-specific errno constant table: name -> value
    private static final Map<String, Integer> ERRNO_TABLE;

    static {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac") || os.contains("darwin")) {
            ERRNO_TABLE = buildDarwinTable();
        } else if (os.contains("win")) {
            ERRNO_TABLE = buildWindowsTable();
        } else {
            ERRNO_TABLE = buildLinuxTable();
        }
    }

    /**
     * Get the current errno value from $!.
     */
    private static int getCurrentErrno() {
        RuntimeScalar errnoVar = GlobalVariable.globalVariables.get("main::!");
        return errnoVar != null ? errnoVar.getInt() : 0;
    }

    /**
     * FETCH: Returns the errno value if $! matches the requested constant,
     * otherwise 0. Returns "" if the constant is unknown.
     */
    @Override
    public RuntimeScalar get(Object key) {
        if (!(key instanceof String name)) return new RuntimeScalar("");
        Integer errval = ERRNO_TABLE.get(name);
        if (errval == null) return new RuntimeScalar("");

        int currentErrno = getCurrentErrno();
        return currentErrno == errval
                ? new RuntimeScalar(errval)
                : new RuntimeScalar(0);
    }

    /**
     * EXISTS: Returns true if the errno constant is known on this platform.
     */
    @Override
    public boolean containsKey(Object key) {
        return key instanceof String && ERRNO_TABLE.containsKey(key);
    }

    /**
     * entrySet: Returns all known errno constants with their current values.
     * Each entry's value is non-zero only if $! currently equals that errno.
     */
    @Override
    public Set<Entry<String, RuntimeScalar>> entrySet() {
        Set<Entry<String, RuntimeScalar>> entries = new HashSet<>();
        int currentErrno = getCurrentErrno();
        for (Map.Entry<String, Integer> e : ERRNO_TABLE.entrySet()) {
            int errval = e.getValue();
            RuntimeScalar val = currentErrno == errval
                    ? new RuntimeScalar(errval)
                    : new RuntimeScalar(0);
            entries.add(new SimpleEntry<>(e.getKey(), val));
        }
        return entries;
    }

    /**
     * size: Returns the number of known errno constants.
     */
    @Override
    public int size() {
        return ERRNO_TABLE.size();
    }

    /**
     * put: %! is read-only. Silently ignore stores (like Perl's STORE which croaks).
     */
    @Override
    public RuntimeScalar put(String key, RuntimeScalar value) {
        // In Perl, STORE on %! calls Carp::confess. For now, silently ignore.
        return null;
    }

    /**
     * remove: %! is read-only. Silently ignore deletes.
     */
    @Override
    public RuntimeScalar remove(Object key) {
        return null;
    }

    // ---- Platform-specific errno constant tables ----
    // These mirror the values in src/main/perl/lib/Errno.pm

    private static Map<String, Integer> buildDarwinTable() {
        Map<String, Integer> m = new HashMap<>();
        m.put("EPERM", 1);
        m.put("ENOENT", 2);
        m.put("ESRCH", 3);
        m.put("EINTR", 4);
        m.put("EIO", 5);
        m.put("ENXIO", 6);
        m.put("E2BIG", 7);
        m.put("ENOEXEC", 8);
        m.put("EBADF", 9);
        m.put("ECHILD", 10);
        m.put("EDEADLK", 11);
        m.put("ENOMEM", 12);
        m.put("EACCES", 13);
        m.put("EFAULT", 14);
        m.put("ENOTBLK", 15);
        m.put("EBUSY", 16);
        m.put("EEXIST", 17);
        m.put("EXDEV", 18);
        m.put("ENODEV", 19);
        m.put("ENOTDIR", 20);
        m.put("EISDIR", 21);
        m.put("EINVAL", 22);
        m.put("ENFILE", 23);
        m.put("EMFILE", 24);
        m.put("ENOTTY", 25);
        m.put("ETXTBSY", 26);
        m.put("EFBIG", 27);
        m.put("ENOSPC", 28);
        m.put("ESPIPE", 29);
        m.put("EROFS", 30);
        m.put("EMLINK", 31);
        m.put("EPIPE", 32);
        m.put("EDOM", 33);
        m.put("ERANGE", 34);
        m.put("EAGAIN", 35);
        m.put("EWOULDBLOCK", 35);
        m.put("EINPROGRESS", 36);
        m.put("EALREADY", 37);
        m.put("ENOTSOCK", 38);
        m.put("EDESTADDRREQ", 39);
        m.put("EMSGSIZE", 40);
        m.put("EPROTOTYPE", 41);
        m.put("ENOPROTOOPT", 42);
        m.put("EPROTONOSUPPORT", 43);
        m.put("ESOCKTNOSUPPORT", 44);
        m.put("ENOTSUP", 45);
        m.put("EOPNOTSUPP", 45);
        m.put("EPFNOSUPPORT", 46);
        m.put("EAFNOSUPPORT", 47);
        m.put("EADDRINUSE", 48);
        m.put("EADDRNOTAVAIL", 49);
        m.put("ENETDOWN", 50);
        m.put("ENETUNREACH", 51);
        m.put("ENETRESET", 52);
        m.put("ECONNABORTED", 53);
        m.put("ECONNRESET", 54);
        m.put("ENOBUFS", 55);
        m.put("EISCONN", 56);
        m.put("ENOTCONN", 57);
        m.put("ESHUTDOWN", 58);
        m.put("ETOOMANYREFS", 59);
        m.put("ETIMEDOUT", 60);
        m.put("ECONNREFUSED", 61);
        m.put("ELOOP", 62);
        m.put("ENAMETOOLONG", 63);
        m.put("EHOSTDOWN", 64);
        m.put("EHOSTUNREACH", 65);
        m.put("ENOTEMPTY", 66);
        m.put("EUSERS", 68);
        m.put("EDQUOT", 69);
        m.put("ESTALE", 70);
        m.put("EREMOTE", 71);
        m.put("ENOLCK", 77);
        m.put("ENOSYS", 78);
        m.put("EOVERFLOW", 84);
        m.put("ECANCELED", 89);
        m.put("EIDRM", 90);
        m.put("ENOMSG", 91);
        m.put("EILSEQ", 92);
        m.put("EBADMSG", 94);
        m.put("EMULTIHOP", 95);
        m.put("ENODATA", 96);
        m.put("ENOLINK", 97);
        m.put("ENOSR", 98);
        m.put("ENOSTR", 99);
        m.put("EPROTO", 100);
        m.put("ETIME", 101);
        m.put("EOWNERDEAD", 105);
        m.put("ENOTRECOVERABLE", 104);
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, Integer> buildLinuxTable() {
        Map<String, Integer> m = new HashMap<>();
        m.put("EPERM", 1);
        m.put("ENOENT", 2);
        m.put("ESRCH", 3);
        m.put("EINTR", 4);
        m.put("EIO", 5);
        m.put("ENXIO", 6);
        m.put("E2BIG", 7);
        m.put("ENOEXEC", 8);
        m.put("EBADF", 9);
        m.put("ECHILD", 10);
        m.put("EAGAIN", 11);
        m.put("EWOULDBLOCK", 11);
        m.put("ENOMEM", 12);
        m.put("EACCES", 13);
        m.put("EFAULT", 14);
        m.put("ENOTBLK", 15);
        m.put("EBUSY", 16);
        m.put("EEXIST", 17);
        m.put("EXDEV", 18);
        m.put("ENODEV", 19);
        m.put("ENOTDIR", 20);
        m.put("EISDIR", 21);
        m.put("EINVAL", 22);
        m.put("ENFILE", 23);
        m.put("EMFILE", 24);
        m.put("ENOTTY", 25);
        m.put("ETXTBSY", 26);
        m.put("EFBIG", 27);
        m.put("ENOSPC", 28);
        m.put("ESPIPE", 29);
        m.put("EROFS", 30);
        m.put("EMLINK", 31);
        m.put("EPIPE", 32);
        m.put("EDOM", 33);
        m.put("ERANGE", 34);
        m.put("EDEADLK", 35);
        m.put("EDEADLOCK", 35);
        m.put("ENAMETOOLONG", 36);
        m.put("ENOLCK", 37);
        m.put("ENOSYS", 38);
        m.put("ENOTEMPTY", 39);
        m.put("ELOOP", 40);
        m.put("ENOMSG", 42);
        m.put("EIDRM", 43);
        m.put("ECHRNG", 44);
        m.put("EL2NSYNC", 45);
        m.put("EL3HLT", 46);
        m.put("EL3RST", 47);
        m.put("ELNRNG", 48);
        m.put("EUNATCH", 49);
        m.put("ENOCSI", 50);
        m.put("EL2HLT", 51);
        m.put("EBADE", 52);
        m.put("EBADR", 53);
        m.put("EXFULL", 54);
        m.put("ENOANO", 55);
        m.put("EBADRQC", 56);
        m.put("EBADSLT", 57);
        m.put("EBFONT", 59);
        m.put("ENOSTR", 60);
        m.put("ENODATA", 61);
        m.put("ETIME", 62);
        m.put("ENOSR", 63);
        m.put("ENONET", 64);
        m.put("ENOPKG", 65);
        m.put("EREMOTE", 66);
        m.put("ENOLINK", 67);
        m.put("EADV", 68);
        m.put("ESRMNT", 69);
        m.put("ECOMM", 70);
        m.put("EPROTO", 71);
        m.put("EMULTIHOP", 72);
        m.put("EDOTDOT", 73);
        m.put("EBADMSG", 74);
        m.put("EOVERFLOW", 75);
        m.put("ENOTUNIQ", 76);
        m.put("EBADFD", 77);
        m.put("EREMCHG", 78);
        m.put("ELIBACC", 79);
        m.put("ELIBBAD", 80);
        m.put("ELIBSCN", 81);
        m.put("ELIBMAX", 82);
        m.put("ELIBEXEC", 83);
        m.put("EILSEQ", 84);
        m.put("ERESTART", 85);
        m.put("ESTRPIPE", 86);
        m.put("EUSERS", 87);
        m.put("ENOTSOCK", 88);
        m.put("EDESTADDRREQ", 89);
        m.put("EMSGSIZE", 90);
        m.put("EPROTOTYPE", 91);
        m.put("ENOPROTOOPT", 92);
        m.put("EPROTONOSUPPORT", 93);
        m.put("ESOCKTNOSUPPORT", 94);
        m.put("ENOTSUP", 95);
        m.put("EOPNOTSUPP", 95);
        m.put("EPFNOSUPPORT", 96);
        m.put("EAFNOSUPPORT", 97);
        m.put("EADDRINUSE", 98);
        m.put("EADDRNOTAVAIL", 99);
        m.put("ENETDOWN", 100);
        m.put("ENETUNREACH", 101);
        m.put("ENETRESET", 102);
        m.put("ECONNABORTED", 103);
        m.put("ECONNRESET", 104);
        m.put("ENOBUFS", 105);
        m.put("EISCONN", 106);
        m.put("ENOTCONN", 107);
        m.put("ESHUTDOWN", 108);
        m.put("ETOOMANYREFS", 109);
        m.put("ETIMEDOUT", 110);
        m.put("ECONNREFUSED", 111);
        m.put("EHOSTDOWN", 112);
        m.put("EHOSTUNREACH", 113);
        m.put("EALREADY", 114);
        m.put("EINPROGRESS", 115);
        m.put("ESTALE", 116);
        m.put("EUCLEAN", 117);
        m.put("ENOTNAM", 118);
        m.put("ENAVAIL", 119);
        m.put("EISNAM", 120);
        m.put("EREMOTEIO", 121);
        m.put("EDQUOT", 122);
        m.put("ENOMEDIUM", 123);
        m.put("EMEDIUMTYPE", 124);
        m.put("ECANCELED", 125);
        m.put("ENOKEY", 126);
        m.put("EKEYEXPIRED", 127);
        m.put("EKEYREVOKED", 128);
        m.put("EKEYREJECTED", 129);
        m.put("EOWNERDEAD", 130);
        m.put("ENOTRECOVERABLE", 131);
        m.put("ERFKILL", 132);
        m.put("EHWPOISON", 133);
        return Collections.unmodifiableMap(m);
    }

    /**
     * Windows (MSVC UCRT) errno values.
     * Basic POSIX errnos (1-42) match Linux; socket errnos use UCRT supplements (100-138).
     */
    private static Map<String, Integer> buildWindowsTable() {
        Map<String, Integer> m = new HashMap<>();
        // Basic POSIX errnos (same values as Linux CRT)
        m.put("EPERM", 1);
        m.put("ENOENT", 2);
        m.put("ESRCH", 3);
        m.put("EINTR", 4);
        m.put("EIO", 5);
        m.put("ENXIO", 6);
        m.put("E2BIG", 7);
        m.put("ENOEXEC", 8);
        m.put("EBADF", 9);
        m.put("ECHILD", 10);
        m.put("EAGAIN", 11);
        m.put("ENOMEM", 12);
        m.put("EACCES", 13);
        m.put("EFAULT", 14);
        m.put("EBUSY", 16);
        m.put("EEXIST", 17);
        m.put("EXDEV", 18);
        m.put("ENODEV", 19);
        m.put("ENOTDIR", 20);
        m.put("EISDIR", 21);
        m.put("EINVAL", 22);
        m.put("ENFILE", 23);
        m.put("EMFILE", 24);
        m.put("ENOTTY", 25);
        m.put("EFBIG", 27);
        m.put("ENOSPC", 28);
        m.put("ESPIPE", 29);
        m.put("EROFS", 30);
        m.put("EMLINK", 31);
        m.put("EPIPE", 32);
        m.put("EDOM", 33);
        m.put("ERANGE", 34);
        m.put("EDEADLK", 36);
        m.put("EDEADLOCK", 36);
        m.put("ENAMETOOLONG", 38);
        m.put("ENOLCK", 39);
        m.put("ENOSYS", 40);
        m.put("ENOTEMPTY", 41);
        m.put("EILSEQ", 42);
        // MSVC UCRT socket/network errno supplements (100-138)
        m.put("EADDRINUSE", 100);
        m.put("EADDRNOTAVAIL", 101);
        m.put("EAFNOSUPPORT", 102);
        m.put("EALREADY", 103);
        m.put("EBADMSG", 104);
        m.put("ECANCELED", 105);
        m.put("ECONNABORTED", 106);
        m.put("ECONNREFUSED", 107);
        m.put("ECONNRESET", 108);
        m.put("EDESTADDRREQ", 109);
        m.put("EHOSTUNREACH", 110);
        m.put("EIDRM", 111);
        m.put("EINPROGRESS", 112);
        m.put("EISCONN", 113);
        m.put("ELOOP", 114);
        m.put("EMSGSIZE", 115);
        m.put("ENETDOWN", 116);
        m.put("ENETRESET", 117);
        m.put("ENETUNREACH", 118);
        m.put("ENOBUFS", 119);
        m.put("ENODATA", 120);
        m.put("ENOLINK", 121);
        m.put("ENOMSG", 122);
        m.put("ENOPROTOOPT", 123);
        m.put("ENOTCONN", 124);
        m.put("ENOTRECOVERABLE", 125);
        m.put("ENOTSOCK", 126);
        m.put("ENOTSUP", 127);
        m.put("EOPNOTSUPP", 127);
        m.put("EOVERFLOW", 128);
        m.put("EOWNERDEAD", 129);
        m.put("EPROTO", 130);
        m.put("EPROTONOSUPPORT", 131);
        m.put("EPROTOTYPE", 132);
        m.put("ETIMEDOUT", 133);
        m.put("ETXTBSY", 134);
        m.put("EWOULDBLOCK", 138);
        return Collections.unmodifiableMap(m);
    }

    /**
     * Used by ErrnoVariable to resolve errno constant names to values.
     */
    static Map<String, Integer> getErrnoTable() {
        return ERRNO_TABLE;
    }
}
