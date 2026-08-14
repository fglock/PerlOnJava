package org.perlonjava.runtime.perlmodule;

import com.sun.security.auth.module.Krb5LoginModule;
import org.perlonjava.runtime.runtimetypes.*;

import javax.security.auth.Subject;
import javax.security.auth.callback.*;
import java.util.HashMap;
import java.util.Map;

/** Authen::Krb5::Simple's XS primitives backed by the JDK Kerberos module. */
public class AuthenKrb5Simple extends PerlModuleBase {
    public static final String XS_VERSION = "0.43";
    private static final int AUTHENTICATION_FAILED = 1;
    private static final ThreadLocal<String> LAST_ERROR = new ThreadLocal<>();

    public AuthenKrb5Simple() {
        super("Authen::Krb5::Simple", false);
    }

    public static void initialize() {
        AuthenKrb5Simple mod = new AuthenKrb5Simple();
        try {
            mod.registerMethod("krb5_auth", null);
            mod.registerMethod("krb5_errstr", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Authen::Krb5::Simple Java binding is incomplete", e);
        }
    }

    public static RuntimeList krb5_auth(RuntimeArray args, int ctx) {
        String principal = args.isEmpty() ? "" : args.get(0).toString();
        char[] password = args.size() < 2 ? new char[0] : args.get(1).toString().toCharArray();
        Krb5LoginModule login = new Krb5LoginModule();
        Map<String, Object> shared = new HashMap<>();
        shared.put("javax.security.auth.login.name", principal);
        shared.put("javax.security.auth.login.password", password);
        Map<String, Object> options = new HashMap<>();
        options.put("useFirstPass", "true");
        options.put("storeKey", "false");
        options.put("doNotPrompt", "true");
        options.put("isInitiator", "true");
        try {
            login.initialize(new Subject(), callbacks(principal, password), shared, options);
            login.login();
            login.commit();
            login.logout();
            LAST_ERROR.remove();
            return new RuntimeScalar(0).getList();
        } catch (Exception e) {
            String message = e.getMessage();
            LAST_ERROR.set(message == null || message.isEmpty()
                    ? "Kerberos authentication failed" : message);
            try { login.abort(); } catch (Exception ignored) { }
            return new RuntimeScalar(AUTHENTICATION_FAILED).getList();
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    public static RuntimeList krb5_errstr(RuntimeArray args, int ctx) {
        String message = LAST_ERROR.get();
        return new RuntimeScalar(message == null ? "Kerberos authentication failed" : message).getList();
    }

    private static CallbackHandler callbacks(String principal, char[] password) {
        return callbacks -> {
            for (Callback callback : callbacks) {
                if (callback instanceof NameCallback name) name.setName(principal);
                else if (callback instanceof PasswordCallback pass) pass.setPassword(password);
                else throw new UnsupportedCallbackException(callback);
            }
        };
    }
}
