package POSIX;

use strict;
use warnings;
use Exporter 'import';

use XSLoader;
XSLoader::load('POSIX');

# Export tags for different groups of functions/constants
our @EXPORT = ();  # Default to exporting nothing
our @EXPORT_OK = qw(
    # Process functions
    _exit abort access alarm chdir chmod chown close ctermid dup dup2
    execl execle execlp execv execve execvp
    fork fpathconf getcwd getegid geteuid getgid getgroups getlogin
    getpgrp getpid getppid getuid isatty kill link lseek mkdir mkfifo
    pathconf pause pipe read rename rmdir setgid setpgid setsid setuid
    sleep sysconf tcdrain tcflow tcflush tcgetpgrp tcsendbreak
    tcsetpgrp time times ttyname tzname umask uname unlink utime wait waitpid write

    # User/Group functions
    getpwnam getpwuid getgrnam getgrgid
    getpwent setpwent endpwent
    getgrent setgrent endgrent

    # Math functions
    abs acos asin atan atan2 ceil cos cosh exp fabs floor fmod frexp
    ldexp log log10 modf pow sin sinh sqrt tan tanh

    # String functions
    memchr memcmp memcpy memmove memset strcat strchr strcmp strcoll
    strcpy strcspn strerror strlen strncat strncmp strncpy strpbrk
    strrchr strspn strstr strtok strxfrm

    # Time functions
    asctime clock ctime difftime gmtime localtime mktime strftime tzset

    # Signal functions
    raise sigaction signal

    # Locale functions
    localeconv setlocale

    # Constants - locale categories
    LC_ALL LC_COLLATE LC_CTYPE LC_MESSAGES LC_MONETARY LC_NUMERIC LC_TIME

    # Constants - errno
    E2BIG EACCES EADDRINUSE EADDRNOTAVAIL EAFNOSUPPORT EAGAIN EALREADY
    EBADF EBADMSG EBUSY ECANCELED ECHILD ECONNABORTED ECONNREFUSED
    ECONNRESET EDEADLK EDESTADDRREQ EDOM EDQUOT EEXIST EFAULT EFBIG
    EHOSTDOWN EHOSTUNREACH EIDRM EILSEQ EINPROGRESS EINTR EINVAL EIO
    EISCONN EISDIR ELOOP EMFILE EMLINK EMSGSIZE ENAMETOOLONG ENETDOWN
    ENETRESET ENETUNREACH ENFILE ENOBUFS ENODEV ENOENT ENOEXEC ENOLCK
    ENOMEM ENOMSG ENOPROTOOPT ENOSPC ENOSYS ENOTBLK ENOTCONN ENOTDIR
    ENOTEMPTY ENOTSOCK ENOTTY ENXIO EOPNOTSUPP EPERM EPFNOSUPPORT EPIPE
    EPROCLIM EPROTONOSUPPORT EPROTOTYPE ERANGE EREMOTE ERESTART EROFS
    ESHUTDOWN ESOCKTNOSUPPORT ESPIPE ESRCH ESTALE ETIMEDOUT ETOOMANYREFS
    ETXTBSY EUSERS EWOULDBLOCK EXDEV

    # Constants - fcntl
    F_DUPFD F_GETFD F_SETFD F_GETFL F_SETFL F_GETLK F_SETLK F_SETLKW
    FD_CLOEXEC F_RDLCK F_UNLCK F_WRLCK
    O_ACCMODE O_APPEND O_CREAT O_EXCL O_NOCTTY O_NONBLOCK O_RDONLY
    O_RDWR O_TRUNC O_WRONLY

    # Constants - limits
    ARG_MAX CHAR_BIT CHAR_MAX CHAR_MIN CHILD_MAX INT_MAX INT_MIN
    LINK_MAX LONG_MAX LONG_MIN MAX_CANON MAX_INPUT MB_LEN_MAX
    NAME_MAX NGROUPS_MAX OPEN_MAX PATH_MAX PIPE_BUF SCHAR_MAX
    SCHAR_MIN SHRT_MAX SHRT_MIN SSIZE_MAX STREAM_MAX TZNAME_MAX
    UCHAR_MAX UINT_MAX ULONG_MAX USHRT_MAX

    # Constants - signal
    SA_NOCLDSTOP SA_NOCLDWAIT SA_NODEFER SA_ONSTACK SA_RESETHAND
    SA_RESTART SA_SIGINFO SIGABRT SIGALRM SIGBUS SIGCHLD SIGCONT
    SIGFPE SIGHUP SIGILL SIGINT SIGKILL SIGPIPE SIGQUIT SIGSEGV
    SIGSTOP SIGTERM SIGTSTP SIGTTIN SIGTTOU SIGUSR1 SIGUSR2
    SIG_BLOCK SIG_DFL SIG_ERR SIG_IGN SIG_SETMASK SIG_UNBLOCK

    # Constants - stat
    S_IRGRP S_IROTH S_IRUSR S_IRWXG S_IRWXO S_IRWXU S_ISGID
    S_ISUID S_IWGRP S_IWOTH S_IWUSR S_IXGRP S_IXOTH S_IXUSR

    # Constants - wait
    WEXITSTATUS WIFEXITED WIFSIGNALED WIFSTOPPED WNOHANG WSTOPSIG
    WTERMSIG WUNTRACED

    # Constants - seek
    SEEK_CUR SEEK_END SEEK_SET
);

our %EXPORT_TAGS = (
    errno_h => [qw(
        E2BIG EACCES EADDRINUSE EADDRNOTAVAIL EAFNOSUPPORT EAGAIN
        EALREADY EBADF EBADMSG EBUSY ECANCELED ECHILD ECONNABORTED
        ECONNREFUSED ECONNRESET EDEADLK EDESTADDRREQ EDOM EDQUOT
        EEXIST EFAULT EFBIG EHOSTDOWN EHOSTUNREACH EIDRM EILSEQ
        EINPROGRESS EINTR EINVAL EIO EISCONN EISDIR ELOOP EMFILE
        EMLINK EMSGSIZE ENAMETOOLONG ENETDOWN ENETRESET ENETUNREACH
        ENFILE ENOBUFS ENODEV ENOENT ENOEXEC ENOLCK ENOMEM ENOMSG
        ENOPROTOOPT ENOSPC ENOSYS ENOTBLK ENOTCONN ENOTDIR ENOTEMPTY
        ENOTSOCK ENOTTY ENXIO EOPNOTSUPP EPERM EPFNOSUPPORT EPIPE
        EPROCLIM EPROTONOSUPPORT EPROTOTYPE ERANGE EREMOTE ERESTART
        EROFS ESHUTDOWN ESOCKTNOSUPPORT ESPIPE ESRCH ESTALE ETIMEDOUT
        ETOOMANYREFS ETXTBSY EUSERS EWOULDBLOCK EXDEV
    )],

    fcntl_h => [qw(
        F_DUPFD F_GETFD F_SETFD F_GETFL F_SETFL F_GETLK F_SETLK
        F_SETLKW FD_CLOEXEC F_RDLCK F_UNLCK F_WRLCK
        O_ACCMODE O_APPEND O_CREAT O_EXCL O_NOCTTY O_NONBLOCK
        O_RDONLY O_RDWR O_TRUNC O_WRONLY
    )],

    signal_h => [qw(
        SA_NOCLDSTOP SA_NOCLDWAIT SA_NODEFER SA_ONSTACK SA_RESETHAND
        SA_RESTART SA_SIGINFO SIGABRT SIGALRM SIGBUS SIGCHLD SIGCONT
        SIGFPE SIGHUP SIGILL SIGINT SIGKILL SIGPIPE SIGQUIT SIGSEGV
        SIGSTOP SIGTERM SIGTSTP SIGTTIN SIGTTOU SIGUSR1 SIGUSR2
        SIG_BLOCK SIG_DFL SIG_ERR SIG_IGN SIG_SETMASK SIG_UNBLOCK
        raise sigaction signal
    )],

    limits_h => [qw(
        ARG_MAX CHAR_BIT CHAR_MAX CHAR_MIN CHILD_MAX INT_MAX INT_MIN
        LINK_MAX LONG_MAX LONG_MIN MAX_CANON MAX_INPUT MB_LEN_MAX
        NAME_MAX NGROUPS_MAX OPEN_MAX PATH_MAX PIPE_BUF SCHAR_MAX
        SCHAR_MIN SHRT_MAX SHRT_MIN SSIZE_MAX STREAM_MAX TZNAME_MAX
        UCHAR_MAX UINT_MAX ULONG_MAX USHRT_MAX
    )],

    wait_h => [qw(
        WEXITSTATUS WIFEXITED WIFSIGNALED WIFSTOPPED WNOHANG
        WSTOPSIG WTERMSIG WUNTRACED wait waitpid
    )],

    unistd_h => [qw(
        _exit access alarm chdir chmod chown close ctermid dup dup2
        execl execle execlp execv execve execvp fork fpathconf
        getcwd getegid geteuid getgid getgroups getlogin getpgrp
        getpid getppid getuid isatty link lseek pathconf pause
        pipe read rmdir setgid setpgid setsid setuid sleep
        sysconf tcdrain tcflow tcflush tcgetpgrp tcsendbreak
        tcsetpgrp ttyname umask unlink write
    )],

    stdio_h => [qw(
        clearerr fclose fdopen feof ferror fflush fgetc fgetpos
        fgets fileno fopen fprintf fputc fputs fread freopen
        fscanf fseek fsetpos ftell fwrite getc getchar gets
        perror printf putc putchar puts remove rename rewind
        scanf setbuf setvbuf sprintf sscanf tmpfile tmpnam
        ungetc vfprintf vprintf vsprintf
    )],

    math_h => [qw(
        abs acos asin atan atan2 ceil cos cosh exp fabs floor
        fmod frexp ldexp log log10 modf pow sin sinh sqrt tan tanh
    )],

    string_h => [qw(
        memchr memcmp memcpy memmove memset strcat strchr strcmp
        strcoll strcpy strcspn strerror strlen strncat strncmp
        strncpy strpbrk strrchr strspn strstr strtok strxfrm
    )],

    time_h => [qw(
        asctime clock ctime difftime gmtime localtime mktime
        strftime time tzname tzset
    )],

    pwd_h => [qw(
        getpwnam getpwuid getpwent setpwent endpwent
    )],

    grp_h => [qw(
        getgrnam getgrgid getgrent setgrent endgrent
    )],
);

# Process management functions
sub fork { POSIX::_fork() }
sub _exit { POSIX::_do_exit(@_) }
sub kill { POSIX::_kill(@_) }
sub wait { POSIX::_wait() }
sub waitpid { POSIX::_waitpid(@_) }
sub getpid { POSIX::_getpid() }
sub getppid { POSIX::_getppid() }
sub getuid { POSIX::_getuid() }
sub geteuid { POSIX::_geteuid() }
sub getgid { POSIX::_getgid() }
sub getegid { POSIX::_getegid() }
sub setuid { POSIX::_setuid(@_) }
sub setgid { POSIX::_setgid(@_) }

# User/Group functions
sub getpwnam { POSIX::_getpwnam(@_) }
sub getpwuid { POSIX::_getpwuid(@_) }
sub getpwent { POSIX::_getpwent() }
sub setpwent { POSIX::_setpwent() }
sub endpwent { POSIX::_endpwent() }
sub getgrnam { POSIX::_getgrnam(@_) }
sub getgrgid { POSIX::_getgrgid(@_) }
sub getgrent { POSIX::_getgrent() }
sub setgrent { POSIX::_setgrent() }
sub endgrent { POSIX::_endgrent() }
sub getlogin { POSIX::_getlogin() }

# File operations
sub open { POSIX::_open(@_) }
sub close { POSIX::_close(@_) }
sub read { POSIX::_read(@_) }
sub write { POSIX::_write(@_) }
sub lseek { POSIX::_lseek(@_) }
sub dup { POSIX::_dup(@_) }
sub dup2 { POSIX::_dup2(@_) }
sub pipe { POSIX::_pipe() }
sub access { POSIX::_access(@_) }
sub chmod { POSIX::_chmod(@_) }
sub chown { POSIX::_chown(@_) }
sub umask { POSIX::_umask(@_) }
sub unlink { POSIX::_unlink(@_) }
sub link { POSIX::_link(@_) }
sub rename { POSIX::_rename(@_) }
sub mkdir { POSIX::_mkdir(@_) }
sub rmdir { POSIX::_rmdir(@_) }
sub getcwd { POSIX::_getcwd() }
sub chdir { POSIX::_chdir(@_) }

# Time functions
sub time { POSIX::_time() }
sub sleep { POSIX::_sleep(@_) }
sub alarm { POSIX::_alarm(@_) }

# Math functions (many can use Perl builtins)
sub abs { CORE::abs($_[0]) }
sub sqrt { CORE::sqrt($_[0]) }
sub exp { CORE::exp($_[0]) }
sub log { CORE::log($_[0]) }
sub sin { CORE::sin($_[0]) }
sub cos { CORE::cos($_[0]) }
sub atan2 { CORE::atan2($_[0], $_[1]) }

# String error function
sub strerror { POSIX::_strerror(@_) }

# Signal functions
sub signal { POSIX::_signal(@_) }
sub raise { POSIX::_raise(@_) }

# Constants - generate subs for each constant
for my $const (qw(
    EINTR ENOENT ESRCH EIO ENXIO E2BIG ENOEXEC EBADF ECHILD EAGAIN
    ENOMEM EACCES EFAULT ENOTBLK EBUSY EEXIST EXDEV ENODEV ENOTDIR
    EISDIR EINVAL ENFILE EMFILE ENOTTY ETXTBSY EFBIG ENOSPC ESPIPE
    EROFS EMLINK EPIPE EDOM ERANGE EPERM

    O_RDONLY O_WRONLY O_RDWR O_CREAT O_EXCL O_NOCTTY O_TRUNC O_APPEND O_NONBLOCK

    SEEK_SET SEEK_CUR SEEK_END

    SIGHUP SIGINT SIGQUIT SIGILL SIGTRAP SIGABRT SIGBUS SIGFPE SIGKILL
    SIGUSR1 SIGSEGV SIGUSR2 SIGPIPE SIGALRM SIGTERM SIGCHLD SIGCONT
    SIGSTOP SIGTSTP

    WNOHANG WUNTRACED

    LC_ALL LC_COLLATE LC_CTYPE LC_MESSAGES LC_MONETARY LC_NUMERIC LC_TIME
)) {
    no strict 'refs';
    *{$const} = eval "sub () { POSIX::_const_$const() }";
}

# Locale category constants fallback (in case XS constants are not available)
BEGIN {
    my %lc = (
        LC_ALL      => 0,
        LC_COLLATE  => 1,
        LC_CTYPE    => 2,
        LC_MONETARY => 3,
        LC_NUMERIC  => 4,
        LC_TIME     => 5,
        LC_MESSAGES => 6,
    );
    no strict 'refs';
    for my $name (keys %lc) {
        *{$name} = sub () { $lc{$name} };
    }
}

# Exit status macros
sub WIFEXITED { POSIX::_WIFEXITED(@_) }
sub WEXITSTATUS { POSIX::_WEXITSTATUS(@_) }
sub WIFSIGNALED { POSIX::_WIFSIGNALED(@_) }
sub WTERMSIG { POSIX::_WTERMSIG(@_) }
sub WIFSTOPPED { POSIX::_WIFSTOPPED(@_) }
sub WSTOPSIG { POSIX::_WSTOPSIG(@_) }

sub DBL_MAX { 1.7976931348623157E308 }

1;

__END__

=head1 NAME

POSIX - Perl interface to IEEE Std 1003.1

=head1 SYNOPSIS

    use POSIX ();
    use POSIX qw(setsid);
    use POSIX qw(:errno_h :fcntl_h);

    printf "EINTR is %d\n", EINTR;

    my $sess_id = POSIX::setsid();

    my $fd = POSIX::open($path, O_CREAT|O_EXCL|O_WRONLY, 0644);

=head1 DESCRIPTION

The POSIX module permits you to access all (or nearly all) the standard
POSIX 1003.1 identifiers. Many of these identifiers have been given
Perl-ish interfaces.

=cut
