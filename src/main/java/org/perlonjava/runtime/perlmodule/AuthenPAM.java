package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

import java.util.Map;

import static org.perlonjava.runtime.runtimetypes.RuntimeScalarCache.scalarUndef;

/**
 * Load-time compatibility backend for Authen::PAM.
 *
 * <p>The CPAN distribution's generated Perl layer provides the public OO and
 * Exporter API.  This class supplies its XS entry points and constants so
 * dependent modules can load on the JVM.  Native PAM conversations are not
 * yet safe to expose without a complete FFM upcall/lifetime implementation;
 * operational entry points therefore return {@code PAM_SYSTEM_ERR} instead
 * of claiming that authentication succeeded.</p>
 */
public final class AuthenPAM extends PerlModuleBase {
    private static final int PAM_SYSTEM_ERR = 4;

    private static final Map<String, Integer> CONSTANTS = Map.ofEntries(
            Map.entry("PAM_SUCCESS", 0),
            Map.entry("PAM_OPEN_ERR", 1),
            Map.entry("PAM_SYMBOL_ERR", 2),
            Map.entry("PAM_SERVICE_ERR", 3),
            Map.entry("PAM_SYSTEM_ERR", PAM_SYSTEM_ERR),
            Map.entry("PAM_BUF_ERR", 5),
            Map.entry("PAM_PERM_DENIED", 6),
            Map.entry("PAM_AUTH_ERR", 7),
            Map.entry("PAM_CRED_INSUFFICIENT", 8),
            Map.entry("PAM_AUTHINFO_UNAVAIL", 9),
            Map.entry("PAM_USER_UNKNOWN", 10),
            Map.entry("PAM_MAXTRIES", 11),
            Map.entry("PAM_NEW_AUTHTOK_REQD", 12),
            Map.entry("PAM_AUTHTOKEN_REQD", 12),
            Map.entry("PAM_ACCT_EXPIRED", 13),
            Map.entry("PAM_SESSION_ERR", 14),
            Map.entry("PAM_CRED_UNAVAIL", 15),
            Map.entry("PAM_CRED_EXPIRED", 16),
            Map.entry("PAM_CRED_ERR", 17),
            Map.entry("PAM_NO_MODULE_DATA", 18),
            Map.entry("PAM_AUTHTOK_ERR", 20),
            Map.entry("PAM_AUTHTOK_RECOVER_ERR", 21),
            Map.entry("PAM_AUTHTOK_RECOVERY_ERR", 21),
            Map.entry("PAM_AUTHTOK_LOCK_BUSY", 22),
            Map.entry("PAM_AUTHTOK_DISABLE_AGING", 23),
            Map.entry("PAM_TRY_AGAIN", 24),
            Map.entry("PAM_IGNORE", 25),
            Map.entry("PAM_ABORT", 26),
            Map.entry("PAM_AUTHTOK_EXPIRED", 27),
            Map.entry("PAM_MODULE_UNKNOWN", 28),
            Map.entry("PAM_BAD_ITEM", 29),
            Map.entry("PAM_CONV_ERR", 19),
            Map.entry("PAM_CONV_AGAIN", 30),
            Map.entry("PAM_INCOMPLETE", 31),
            Map.entry("PAM_SERVICE", 1),
            Map.entry("PAM_USER", 2),
            Map.entry("PAM_TTY", 3),
            Map.entry("PAM_RHOST", 4),
            Map.entry("PAM_CONV", 5),
            Map.entry("PAM_AUTHTOK", 6),
            Map.entry("PAM_OLDAUTHTOK", 7),
            Map.entry("PAM_RUSER", 8),
            Map.entry("PAM_USER_PROMPT", 9),
            Map.entry("PAM_FAIL_DELAY", 10),
            Map.entry("PAM_SILENT", 0x8000),
            Map.entry("PAM_DISALLOW_NULL_AUTHTOK", 0x0001),
            Map.entry("PAM_ESTABLISH_CRED", 0x0002),
            Map.entry("PAM_CRED_ESTABLISH", 0x0002),
            Map.entry("PAM_DELETE_CRED", 0x0004),
            Map.entry("PAM_CRED_DELETE", 0x0004),
            Map.entry("PAM_REINITIALIZE_CRED", 0x0008),
            Map.entry("PAM_CRED_REINITIALIZE", 0x0008),
            Map.entry("PAM_REFRESH_CRED", 0x0010),
            Map.entry("PAM_CRED_REFRESH", 0x0010),
            Map.entry("PAM_CHANGE_EXPIRED_AUTHTOK", 0x0020),
            Map.entry("PAM_PROMPT_ECHO_OFF", 1),
            Map.entry("PAM_PROMPT_ECHO_ON", 2),
            Map.entry("PAM_ERROR_MSG", 3),
            Map.entry("PAM_TEXT_INFO", 4),
            Map.entry("PAM_RADIO_TYPE", 5),
            Map.entry("PAM_BINARY_PROMPT", 7),
            Map.entry("PAM_MAX_MSG_SIZE", 512),
            Map.entry("PAM_MAX_RESP_SIZE", 512),
            Map.entry("HAVE_PAM_FAIL_DELAY", 0),
            Map.entry("HAVE_PAM_ENV_FUNCTIONS", 0),
            Map.entry("HAVE_PAM_SYSTEM_LOG", 0)
    );

    private AuthenPAM() {
        super("Authen::PAM", false);
    }

    public static void initialize() {
        AuthenPAM module = new AuthenPAM();
        try {
            module.registerMethod("constant", null);
            module.registerMethod("_pam_start", "unsupported", null);
            module.registerMethod("pam_end", "unsupported", null);
            module.registerMethod("pam_set_item", "unsupported", null);
            module.registerMethod("pam_get_item", "unsupported", null);
            module.registerMethod("pam_putenv", "unsupported", null);
            module.registerMethod("pam_fail_delay", "unsupported", null);
            module.registerMethod("pam_authenticate", "unsupported", null);
            module.registerMethod("pam_setcred", "unsupported", null);
            module.registerMethod("pam_acct_mgmt", "unsupported", null);
            module.registerMethod("pam_open_session", "unsupported", null);
            module.registerMethod("pam_close_session", "unsupported", null);
            module.registerMethod("pam_chauthtok", "unsupported", null);
            module.registerMethod("pam_strerror", null);
            module.registerMethod("pam_getenv", null);
            module.registerMethod("_pam_getenvlist", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Unable to initialize Authen::PAM", e);
        }
    }

    public static RuntimeList constant(RuntimeArray args, int ctx) {
        String name = args.isEmpty() ? "" : args.get(0).toString();
        Integer value = CONSTANTS.get(name);
        if (value == null) {
            // EINVAL is the contract used by Authen::PAM's AUTOLOAD fallback.
            GlobalVariable.getGlobalVariable("main::!").set(new RuntimeScalar(22));
            return new RuntimeScalar(0).getList();
        }
        GlobalVariable.getGlobalVariable("main::!").set(new RuntimeScalar(0));
        return new RuntimeScalar(value).getList();
    }

    public static RuntimeList unsupported(RuntimeArray args, int ctx) {
        return new RuntimeScalar(PAM_SYSTEM_ERR).getList();
    }

    public static RuntimeList pam_strerror(RuntimeArray args, int ctx) {
        int error = args.size() > 1 ? args.get(1).getInt()
                : args.isEmpty() ? PAM_SYSTEM_ERR : args.get(0).getInt();
        String message = error == PAM_SYSTEM_ERR
                ? "System error: native PAM conversations are not available in PerlOnJava"
                : "PAM error " + error;
        return new RuntimeScalar(message).getList();
    }

    public static RuntimeList pam_getenv(RuntimeArray args, int ctx) {
        return scalarUndef.getList();
    }

    public static RuntimeList _pam_getenvlist(RuntimeArray args, int ctx) {
        return new RuntimeList();
    }
}
