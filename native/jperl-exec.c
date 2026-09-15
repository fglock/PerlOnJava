/*
 * Small POSIX executable shim used as PerlOnJava's $^X.
 *
 * A script whose shebang names the shell-based jperl launcher cannot be
 * executed directly on macOS: the kernel does not reliably chain script
 * interpreters.  This binary is the first interpreter instead, and in turn
 * starts the regular launcher under bash.
 */
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    const char *launcher = getenv("PERLONJAVA_EXECUTABLE");
    char fallback[PATH_MAX];

    if ((launcher == NULL || launcher[0] == '\0') && strchr(argv[0], '/') != NULL) {
        snprintf(fallback, sizeof(fallback), "%s", argv[0]);
        char *slash = strrchr(fallback, '/');
        *slash = '\0';
        size_t directoryLength = strlen(fallback);
        snprintf(fallback + directoryLength, sizeof(fallback) - directoryLength, "/jperl");
        if (access(fallback, X_OK) != 0) {
            // Development builds keep the shim in target/ beside the repository launcher.
            slash = strrchr(fallback, '/');
            *slash = '\0';
            slash = strrchr(fallback, '/');
            if (slash != NULL) {
                *slash = '\0';
                size_t parentLength = strlen(fallback);
                snprintf(fallback + parentLength, sizeof(fallback) - parentLength, "/jperl");
            }
        }
        launcher = fallback;
    }
    if (launcher == NULL || launcher[0] == '\0') {
        fputs("jperl-exec: PERLONJAVA_EXECUTABLE is not set\n", stderr);
        return 127;
    }

    char **command = calloc((size_t)argc + 2, sizeof(*command));
    if (command == NULL) {
        perror("jperl-exec: calloc");
        return 127;
    }
    command[0] = "/bin/bash";
    command[1] = (char *) launcher;
    for (int i = 1; i < argc; i++) command[i + 1] = argv[i];
    execv(command[0], command);
    fprintf(stderr, "jperl-exec: cannot start %s: %s\n", launcher, strerror(errno));
    return 127;
}
