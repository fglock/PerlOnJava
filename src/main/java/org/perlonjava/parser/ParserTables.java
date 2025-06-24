package org.perlonjava.parser;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class ParserTables {
    // Set of tokens that signify the end of an expression or statement.
    public static final Set<String> TERMINATORS =
            Set.of(":", ";", ")", "}", "]", "if", "unless", "while", "until", "for", "foreach", "when");
    // Set of tokens that can terminate a list of expressions.
    public static final Set<String> LIST_TERMINATORS =
            Set.of(":", ";", ")", "}", "]", "if", "unless", "while", "until", "for", "foreach", "when", "not", "and", "or");
    // Set of infix operators recognized by the parser.
    public static final Set<String> INFIX_OP = Set.of(
            "or", "xor", "and", "||", "//", "&&", "|", "^", "^^", "&", "|.", "^.", "&.",
            "==", "!=", "<=>", "eq", "ne", "cmp", "<", ">", "<=", ">=",
            "lt", "gt", "le", "ge", "<<", ">>", "+", "-", "*",
            "**", "/", "%", ".", "=", "**=", "+=", "*=", "&=", "&.=",
            "<<=", "&&=", "-=", "/=", "|=", "|.=", ">>=", "||=", ".=",
            "%=", "^=", "^.=", "//=", "x=", "=~", "!~", "x", "..", "...", "isa"
    );
    // Map of CORE operators to prototype strings
    public static final Map<String, String> CORE_PROTOTYPES = new HashMap<>();
    // Set of operators that are right associative.
    static final Set<String> RIGHT_ASSOC_OP = Set.of(
            "=", "**=", "+=", "*=", "&=", "&.=", "<<=", "&&=", "-=", "/=", "|=", "|.=",
            ">>=", "||=", ".=", "%=", "^=", "^.=", "//=", "x=", "**", "?"
    );
    // Map to store operator precedence values.
    static final Map<String, Integer> precedenceMap = new HashMap<>();
    // The list below was obtained by running this in the perl git:
    // ack  'CORE::GLOBAL::\w+' | perl -n -e ' /CORE::GLOBAL::(\w+)/ && print $1, "\n" ' | sort -u
    static final Set<String> OVERRIDABLE_OP = Set.of(
            "caller", "chdir", "close", "connect",
            "die", "do",
            "exit",
            "fork",
            "getpwuid", "glob",
            "hex",
            "kill",
            "oct", "open",
            "readline", "readpipe", "rename", "require",
            "stat",
            "time",
            "uc",
            "warn"
    );

    // Static block to initialize the CORE prototypes.
    static {
        CORE_PROTOTYPES.put("abs", "_");
        CORE_PROTOTYPES.put("accept", "**");
        CORE_PROTOTYPES.put("alarm", "_");
        CORE_PROTOTYPES.put("and", null);
        CORE_PROTOTYPES.put("atan2", "$$");
        CORE_PROTOTYPES.put("bind", "*$");
        CORE_PROTOTYPES.put("binmode", "*;$");
        CORE_PROTOTYPES.put("bless", "$;$");
        CORE_PROTOTYPES.put("break", "");
        CORE_PROTOTYPES.put("caller", ";$");
        CORE_PROTOTYPES.put("catch", "");
        CORE_PROTOTYPES.put("chdir", ";$");
        CORE_PROTOTYPES.put("chmod", "@");
        CORE_PROTOTYPES.put("chomp", null);
        CORE_PROTOTYPES.put("chop", null);
        CORE_PROTOTYPES.put("chown", "@");
        CORE_PROTOTYPES.put("chr", "_");
        CORE_PROTOTYPES.put("chroot", "_");
        CORE_PROTOTYPES.put("class", null);
        CORE_PROTOTYPES.put("close", ";*");
        CORE_PROTOTYPES.put("closedir", "*");
        CORE_PROTOTYPES.put("cmp", null);
        CORE_PROTOTYPES.put("connect", "*$");
        CORE_PROTOTYPES.put("continue", "");
        CORE_PROTOTYPES.put("cos", "_");
        CORE_PROTOTYPES.put("crypt", "$$");
        CORE_PROTOTYPES.put("dbmclose", "\\%");
        CORE_PROTOTYPES.put("dbmopen", "\\%$$");
        CORE_PROTOTYPES.put("default", null);
        CORE_PROTOTYPES.put("defer", null);
        CORE_PROTOTYPES.put("defined", null);
        CORE_PROTOTYPES.put("delete", null);
        CORE_PROTOTYPES.put("die", "@");
        CORE_PROTOTYPES.put("do", null);
        CORE_PROTOTYPES.put("dump", "");
        CORE_PROTOTYPES.put("each", "\\[%@]");
        CORE_PROTOTYPES.put("else", null);
        CORE_PROTOTYPES.put("elsif", null);
        CORE_PROTOTYPES.put("endgrent", "");
        CORE_PROTOTYPES.put("endhostent", "");
        CORE_PROTOTYPES.put("endnetent", "");
        CORE_PROTOTYPES.put("endprotoent", "");
        CORE_PROTOTYPES.put("endpwent", "");
        CORE_PROTOTYPES.put("endservent", "");
        CORE_PROTOTYPES.put("eof", ";*");
        CORE_PROTOTYPES.put("eq", null);
        CORE_PROTOTYPES.put("eval", null);
        CORE_PROTOTYPES.put("evalbytes", "_");
        CORE_PROTOTYPES.put("exec", null);
        CORE_PROTOTYPES.put("exists", null);
        CORE_PROTOTYPES.put("exit", ";$");
        CORE_PROTOTYPES.put("exp", "_");
        CORE_PROTOTYPES.put("fc", "_");
        CORE_PROTOTYPES.put("fcntl", "*$$");
        CORE_PROTOTYPES.put("field", null);
        CORE_PROTOTYPES.put("fileno", "*");
        CORE_PROTOTYPES.put("finally", null);
        CORE_PROTOTYPES.put("flock", "*$");
        CORE_PROTOTYPES.put("for", null);
        CORE_PROTOTYPES.put("foreach", null);
        CORE_PROTOTYPES.put("fork", "");
        CORE_PROTOTYPES.put("format", null);
        CORE_PROTOTYPES.put("formline", "$@");
        CORE_PROTOTYPES.put("ge", null);
        CORE_PROTOTYPES.put("getc", ";*");
        CORE_PROTOTYPES.put("getgrent", "");
        CORE_PROTOTYPES.put("getgrgid", "$");
        CORE_PROTOTYPES.put("getgrnam", "$");
        CORE_PROTOTYPES.put("gethostbyaddr", "$$");
        CORE_PROTOTYPES.put("gethostbyname", "$");
        CORE_PROTOTYPES.put("gethostent", "");
        CORE_PROTOTYPES.put("getlogin", "");
        CORE_PROTOTYPES.put("getnetbyaddr", "$$");
        CORE_PROTOTYPES.put("getnetbyname", "$");
        CORE_PROTOTYPES.put("getnetent", "");
        CORE_PROTOTYPES.put("getpeername", "*");
        CORE_PROTOTYPES.put("getpgrp", ";$");
        CORE_PROTOTYPES.put("getppid", "");
        CORE_PROTOTYPES.put("getpriority", "$$");
        CORE_PROTOTYPES.put("getprotobyname", "$");
        CORE_PROTOTYPES.put("getprotobynumber", "$;");
        CORE_PROTOTYPES.put("getprotoent", "");
        CORE_PROTOTYPES.put("getpwent", "");
        CORE_PROTOTYPES.put("getpwnam", "$");
        CORE_PROTOTYPES.put("getpwuid", "$");
        CORE_PROTOTYPES.put("getservbyname", "$$");
        CORE_PROTOTYPES.put("getservbyport", "$$");
        CORE_PROTOTYPES.put("getservent", "");
        CORE_PROTOTYPES.put("getsockname", "*");
        CORE_PROTOTYPES.put("getsockopt", "*$$");
        CORE_PROTOTYPES.put("given", null);
        CORE_PROTOTYPES.put("glob", "_;");
        CORE_PROTOTYPES.put("gmtime", ";$");
        CORE_PROTOTYPES.put("goto", null);
        CORE_PROTOTYPES.put("grep", null);
        CORE_PROTOTYPES.put("gt", null);
        CORE_PROTOTYPES.put("hex", "_");
        CORE_PROTOTYPES.put("if", null);
        CORE_PROTOTYPES.put("index", "$$;$");
        CORE_PROTOTYPES.put("int", "_");
        CORE_PROTOTYPES.put("ioctl", "*$$");
        CORE_PROTOTYPES.put("isa", "");
        CORE_PROTOTYPES.put("join", "$@");
        CORE_PROTOTYPES.put("keys", "\\[%@]");
        CORE_PROTOTYPES.put("kill", "@");
        CORE_PROTOTYPES.put("last", null);
        CORE_PROTOTYPES.put("lc", "_");
        CORE_PROTOTYPES.put("lcfirst", "_");
        CORE_PROTOTYPES.put("le", null);
        CORE_PROTOTYPES.put("length", "_");
        CORE_PROTOTYPES.put("link", "$$");
        CORE_PROTOTYPES.put("listen", "*$");
        CORE_PROTOTYPES.put("local", null);
        CORE_PROTOTYPES.put("localtime", ";$");
        CORE_PROTOTYPES.put("lock", "\\[$@%&*]");
        CORE_PROTOTYPES.put("log", "_");
        CORE_PROTOTYPES.put("lstat", ";*");
        CORE_PROTOTYPES.put("lt", null);
        CORE_PROTOTYPES.put("m", null);
        CORE_PROTOTYPES.put("map", null);
        CORE_PROTOTYPES.put("method", "");
        CORE_PROTOTYPES.put("mkdir", "_;$");
        CORE_PROTOTYPES.put("msgctl", "$$$");
        CORE_PROTOTYPES.put("msgget", "$$");
        CORE_PROTOTYPES.put("msgrcv", "$$$$$");
        CORE_PROTOTYPES.put("msgsnd", "$$$");
        CORE_PROTOTYPES.put("my", null);
        CORE_PROTOTYPES.put("ne", null);
        CORE_PROTOTYPES.put("next", null);
        CORE_PROTOTYPES.put("no", null);
        CORE_PROTOTYPES.put("not", "$;");
        CORE_PROTOTYPES.put("oct", "_");
        CORE_PROTOTYPES.put("open", "*;$@");
        CORE_PROTOTYPES.put("opendir", "*$");
        CORE_PROTOTYPES.put("or", null);
        CORE_PROTOTYPES.put("ord", "_");
        CORE_PROTOTYPES.put("our", null);
        CORE_PROTOTYPES.put("pack", "$@");
        CORE_PROTOTYPES.put("package", null);
        CORE_PROTOTYPES.put("pipe", "**");
        CORE_PROTOTYPES.put("pop", ";\\@");
        CORE_PROTOTYPES.put("pos", ";\\[$*]");
        CORE_PROTOTYPES.put("print", null);
        CORE_PROTOTYPES.put("printf", null);
        CORE_PROTOTYPES.put("prototype", "_");
        CORE_PROTOTYPES.put("push", "\\@@");
        CORE_PROTOTYPES.put("q", null);
        CORE_PROTOTYPES.put("qq", null);
        CORE_PROTOTYPES.put("qr", null);
        CORE_PROTOTYPES.put("quotemeta", "_");
        CORE_PROTOTYPES.put("qw", null);
        CORE_PROTOTYPES.put("qx", null);
        CORE_PROTOTYPES.put("rand", ";$");
        CORE_PROTOTYPES.put("read", "*\\$$;$");
        CORE_PROTOTYPES.put("readdir", "*");
        CORE_PROTOTYPES.put("readline", ";*");
        CORE_PROTOTYPES.put("readlink", "_");
        CORE_PROTOTYPES.put("readpipe", "_");
        CORE_PROTOTYPES.put("recv", "*\\$$$");
        CORE_PROTOTYPES.put("redo", null);
        CORE_PROTOTYPES.put("ref", "_");
        CORE_PROTOTYPES.put("rename", "$$");
        CORE_PROTOTYPES.put("require", null);
        CORE_PROTOTYPES.put("reset", ";$");
        CORE_PROTOTYPES.put("return", null);
        CORE_PROTOTYPES.put("reverse", "@");
        CORE_PROTOTYPES.put("rewinddir", "*");
        CORE_PROTOTYPES.put("rindex", "$$;$");
        CORE_PROTOTYPES.put("rmdir", "_");
        CORE_PROTOTYPES.put("s", null);
        CORE_PROTOTYPES.put("say", null);
        CORE_PROTOTYPES.put("scalar", "$");
        CORE_PROTOTYPES.put("seek", "*$$");
        CORE_PROTOTYPES.put("seekdir", "*$");
        CORE_PROTOTYPES.put("select", null);
        CORE_PROTOTYPES.put("semctl", "$$$$");
        CORE_PROTOTYPES.put("semget", "$$$");
        CORE_PROTOTYPES.put("semop", "$$");
        CORE_PROTOTYPES.put("send", "*$$;$");
        CORE_PROTOTYPES.put("setgrent", "");
        CORE_PROTOTYPES.put("sethostent", "$");
        CORE_PROTOTYPES.put("setnetent", "$");
        CORE_PROTOTYPES.put("setpgrp", ";$$");
        CORE_PROTOTYPES.put("setpriority", "$$$");
        CORE_PROTOTYPES.put("setprotoent", "$");
        CORE_PROTOTYPES.put("setpwent", "");
        CORE_PROTOTYPES.put("setservent", "$");
        CORE_PROTOTYPES.put("setsockopt", "*$$$");
        CORE_PROTOTYPES.put("shift", ";\\@");
        CORE_PROTOTYPES.put("shmctl", "$$$");
        CORE_PROTOTYPES.put("shmget", "$$$");
        CORE_PROTOTYPES.put("shmread", "$$$$");
        CORE_PROTOTYPES.put("shmwrite", "$$$$");
        CORE_PROTOTYPES.put("shutdown", "*$");
        CORE_PROTOTYPES.put("sin", "_");
        CORE_PROTOTYPES.put("sleep", ";$");
        CORE_PROTOTYPES.put("socket", "*$$$");
        CORE_PROTOTYPES.put("socketpair", "**$$$");
        CORE_PROTOTYPES.put("sort", null);
        CORE_PROTOTYPES.put("splice", "\\@;$$@");
        CORE_PROTOTYPES.put("split", null);
        CORE_PROTOTYPES.put("sprintf", "$@");
        CORE_PROTOTYPES.put("sqrt", "_");
        CORE_PROTOTYPES.put("srand", ";$");
        CORE_PROTOTYPES.put("stat", ";*");
        CORE_PROTOTYPES.put("state", null);
        CORE_PROTOTYPES.put("study", "_");
        CORE_PROTOTYPES.put("sub", null);
        CORE_PROTOTYPES.put("substr", "$$;$$");
        CORE_PROTOTYPES.put("symlink", "$$");
        CORE_PROTOTYPES.put("syscall", "$@");
        CORE_PROTOTYPES.put("sysopen", "*$$;$");
        CORE_PROTOTYPES.put("sysread", "*\\$$;$");
        CORE_PROTOTYPES.put("sysseek", "*$$");
        CORE_PROTOTYPES.put("system", null);
        CORE_PROTOTYPES.put("syswrite", "*$;$$");
        CORE_PROTOTYPES.put("tell", ";*");
        CORE_PROTOTYPES.put("telldir", "*");
        CORE_PROTOTYPES.put("tie", "\\[$@%*]$@");
        CORE_PROTOTYPES.put("tied", "\\[$@%*]");
        CORE_PROTOTYPES.put("time", "");
        CORE_PROTOTYPES.put("times", "");
        CORE_PROTOTYPES.put("tr", null);
        CORE_PROTOTYPES.put("truncate", "$$");
        CORE_PROTOTYPES.put("try", null);
        CORE_PROTOTYPES.put("uc", "_");
        CORE_PROTOTYPES.put("ucfirst", "_");
        CORE_PROTOTYPES.put("umask", ";$");
        CORE_PROTOTYPES.put("undef", ";\\[$@%&*]");
        CORE_PROTOTYPES.put("unless", null);
        CORE_PROTOTYPES.put("unlink", "@");
        CORE_PROTOTYPES.put("unpack", "$_");
        CORE_PROTOTYPES.put("unshift", "\\@@");
        CORE_PROTOTYPES.put("untie", "\\[$@%*]");
        CORE_PROTOTYPES.put("until", null);
        CORE_PROTOTYPES.put("use", null);
        CORE_PROTOTYPES.put("utime", "@");
        CORE_PROTOTYPES.put("values", "\\[%@]");
        CORE_PROTOTYPES.put("vec", "$$$");
        CORE_PROTOTYPES.put("wait", "");
        CORE_PROTOTYPES.put("waitpid", "$$");
        CORE_PROTOTYPES.put("wantarray", "");
        CORE_PROTOTYPES.put("warn", "@");
        CORE_PROTOTYPES.put("when", null);
        CORE_PROTOTYPES.put("while", null);
        CORE_PROTOTYPES.put("write", ";*");
        CORE_PROTOTYPES.put("x", null);
        CORE_PROTOTYPES.put("xor", null);
        CORE_PROTOTYPES.put("y", null);
    }

    // Static block to initialize the precedence map with operators and their precedence levels.
    static {
        addOperatorsToMap(1, "or", "xor");
        addOperatorsToMap(2, "and");
        addOperatorsToMap(3, "not");
        addOperatorsToMap(4, "print");
        addOperatorsToMap(5, ",", "=>");
        addOperatorsToMap(6, "=", "**=", "+=", "*=", "&=", "&.=", "<<=", "&&=", "-=", "/=", "|=", "|.=", ">>=", "||=", ".=", "%=", "^=", "^.=", "//=", "x=");
        addOperatorsToMap(7, "?");
        addOperatorsToMap(8, "..", "...");
        addOperatorsToMap(9, "||", "^^", "//");
        addOperatorsToMap(10, "&&");
        addOperatorsToMap(11, "|", "^", "|.", "^.");
        addOperatorsToMap(12, "&", "&.");
        addOperatorsToMap(13, "==", "!=", "<=>", "eq", "ne", "cmp");
        addOperatorsToMap(14, "<", ">", "<=", ">=", "lt", "gt", "le", "ge");
        addOperatorsToMap(15, "isa");
        addOperatorsToMap(16, "-d");
        addOperatorsToMap(17, ">>", "<<");
        addOperatorsToMap(18, "+", "-", ".");
        addOperatorsToMap(19, "*", "/", "%", "x");
        addOperatorsToMap(20, "=~", "!~");
        addOperatorsToMap(21, "!", "~", "~.", "\\");
        addOperatorsToMap(22, "**");
        addOperatorsToMap(23, "++", "--");
        addOperatorsToMap(24, "->");
    }

    /**
     * Adds operators to the precedence map with the specified precedence level.
     *
     * @param precedence The precedence level.
     * @param operators  The operators to add.
     */
    private static void addOperatorsToMap(int precedence, String... operators) {
        for (String operator : operators) {
            precedenceMap.put(operator, precedence);
        }
    }
}
