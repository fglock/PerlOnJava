package POSIX;
our $VERSION = '2.21';

#
# Original POSIX module is part of the Perl core, maintained by the Perl 5 Porters.
#
# PerlOnJava implementation by Flavio S. Glock.
# The XS implementation is in: src/main/java/org/perlonjava/perlmodule/Posix.java
#

use strict;
use warnings;
use Carp;
use Exporter ();

use XSLoader;
XSLoader::load('POSIX');

# Import S_IS* file type test functions from Fcntl for re-export
use Fcntl qw(S_ISBLK S_ISCHR S_ISDIR S_ISFIFO S_ISLNK S_ISREG S_ISSOCK);

# Define O_* constants directly (same values as Fcntl.pm)
# These are needed by many modules that use POSIX
use constant O_RDONLY   => 0;
use constant O_WRONLY   => 1;
use constant O_RDWR     => 2;
use constant O_CREAT    => 0100;    # 64 in decimal
use constant O_EXCL     => 0200;    # 128
use constant O_NOCTTY   => 0400;    # 256
use constant O_TRUNC    => 01000;   # 512
use constant O_APPEND   => 02000;   # 1024
use constant O_NONBLOCK => 04000;   # 2048

# Wait constants
use constant WNOHANG    => 1;
use constant WUNTRACED  => 2;

# Custom import to support legacy foo_h form (without colon)
# This rewrites locale_h to :locale_h, errno_h to :errno_h, etc.
sub import {
    my $pkg = shift;
    my @list = @_;
    # Rewrite legacy foo_h form to new :foo_h form
    s/^(?=\w+_h$)/:/ for @list;
    local $Exporter::ExportLevel = 1;
    Exporter::import($pkg, @list);
}

# Export tags for different groups of functions/constants
# Native Perl's POSIX exports many constants by default
# Only export constants that are actually implemented in this module
our @EXPORT = qw(
    O_RDONLY O_WRONLY O_RDWR O_CREAT O_EXCL O_NOCTTY O_TRUNC O_APPEND O_NONBLOCK
    WEXITSTATUS WIFEXITED WIFSIGNALED WIFSTOPPED WSTOPSIG WTERMSIG WCOREDUMP
    WNOHANG WUNTRACED
    SEEK_CUR SEEK_END SEEK_SET
    F_OK R_OK W_OK X_OK
);
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
    raise sigaction sigprocmask signal
    SigSet SigAction SIG_SETMASK

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

    # Functions - stat file type tests (re-exported from Fcntl)
    S_ISBLK S_ISCHR S_ISDIR S_ISFIFO S_ISLNK S_ISREG S_ISSOCK

    # Constants - wait
    WEXITSTATUS WIFEXITED WIFSIGNALED WIFSTOPPED WNOHANG WSTOPSIG
    WTERMSIG WUNTRACED

    # Constants - seek
    SEEK_CUR SEEK_END SEEK_SET

    # Constants - access (for access() function)
    F_OK R_OK W_OK X_OK

    # Constants - termios (termios_h)
    BRKINT
    CS5 CS6 CS7 CS8 CSIZE CSTOPB CREAD PARENB PARODD HUPCL CLOCAL
    ECHO ECHOE ECHOK ECHONL
    ICANON IEXTEN ISIG
    ICRNL INPCK ISTRIP IXON IXOFF IGNBRK IGNCR IGNPAR INLCR IXANY PARMRK
    OPOST
    TCSADRAIN TCSAFLUSH TCSANOW
    VEOF VEOL VERASE VINTR VKILL VMIN VQUIT VSTART VSTOP VSUSP VTIME

    # Constants - sysconf (subset, used by POE etc.)
    _SC_ARG_MAX _SC_CHILD_MAX _SC_CLK_TCK _SC_NGROUPS_MAX _SC_OPEN_MAX
    _SC_JOB_CONTROL _SC_SAVED_IDS _SC_VERSION _SC_PAGESIZE _SC_PAGE_SIZE
    _SC_NPROCESSORS_CONF _SC_NPROCESSORS_ONLN
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
        SEEK_CUR SEEK_END SEEK_SET
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

    # Alias for sys_wait_h (used by some modules)
    sys_wait_h => [qw(
        WEXITSTATUS WIFEXITED WIFSIGNALED WIFSTOPPED WNOHANG
        WSTOPSIG WTERMSIG WUNTRACED wait waitpid
    )],

    termios_h => [qw(
        B0 B50 B75 B110 B134 B150 B200 B300 B600 B1200 B1800 B2400
        B4800 B9600 B19200 B38400
        BRKINT
        CS5 CS6 CS7 CS8 CSIZE CSTOPB CREAD PARENB PARODD HUPCL CLOCAL
        ECHO ECHOE ECHOK ECHONL
        ICANON IEXTEN ISIG
        ICRNL INPCK ISTRIP IXON IXOFF IGNBRK IGNCR IGNPAR INLCR IXANY PARMRK
        OPOST
        TCSADRAIN TCSAFLUSH TCSANOW
        VEOF VEOL VERASE VINTR VKILL VMIN VQUIT VSTART VSTOP VSUSP VTIME
        cfgetispeed cfgetospeed cfsetispeed cfsetospeed
        tcdrain tcflow tcflush tcgetattr tcsendbreak tcsetattr
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

    locale_h => [qw(
        LC_ALL LC_COLLATE LC_CTYPE LC_MESSAGES LC_MONETARY LC_NUMERIC LC_TIME
        localeconv setlocale
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

# Locale support (stubbed — PerlOnJava does not switch C library locales,
# but many modules rely on these existing and being callable).
sub LC_ALL      () { 0 }
sub LC_COLLATE  () { 1 }
sub LC_CTYPE    () { 2 }
sub LC_MONETARY () { 3 }
sub LC_NUMERIC  () { 4 }
sub LC_TIME     () { 5 }
sub LC_MESSAGES () { 6 }

sub setlocale {
    my ($category, $locale) = @_;
    # Returning the requested locale (or the current/default one) is enough
    # for callers that use setlocale() purely for its return value, e.g.
    # `setlocale(LC_COLLATE, "C")`.
    return defined $locale ? $locale : 'C';
}

sub localeconv {
    return {
        decimal_point   => '.',
        thousands_sep   => '',
        grouping        => '',
        int_curr_symbol => '',
        currency_symbol => '',
        mon_decimal_point => '',
        mon_thousands_sep => '',
        mon_grouping    => '',
        positive_sign   => '',
        negative_sign   => '-',
        int_frac_digits => -1,
        frac_digits     => -1,
        p_cs_precedes   => -1,
        p_sep_by_space  => -1,
        n_cs_precedes   => -1,
        n_sep_by_space  => -1,
        p_sign_posn     => -1,
        n_sign_posn     => -1,
    };
}

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
sub mkfifo { POSIX::_mkfifo(@_) }
sub rmdir { POSIX::_rmdir(@_) }
sub getcwd { POSIX::_getcwd() }
sub chdir { POSIX::_chdir(@_) }

# File control
sub fcntl { POSIX::_fcntl(@_) }

# Terminal functions
sub isatty {
    my $fd = ref($_[0]) ? fileno($_[0]) : $_[0];
    return POSIX::_isatty($fd);
}
sub setsid { POSIX::_setsid() }
sub ttyname {
    my $fd = ref($_[0]) ? fileno($_[0]) : $_[0];
    return POSIX::_ttyname($fd);
}

# Time functions
sub time { POSIX::_time() }
sub sleep { POSIX::_sleep(@_) }
sub alarm { POSIX::_alarm(@_) }
sub strftime { POSIX::_strftime(@_) }
sub mktime { POSIX::_mktime(@_) }

# Math functions (many can use Perl builtins)
sub abs { CORE::abs($_[0]) }
sub sqrt { CORE::sqrt($_[0]) }
sub exp { CORE::exp($_[0]) }
sub log { CORE::log($_[0]) }
sub sin { CORE::sin($_[0]) }
sub cos { CORE::cos($_[0]) }
sub atan2 { CORE::atan2($_[0], $_[1]) }
sub floor { CORE::int($_[0] >= 0 ? $_[0] : ($_[0] == CORE::int($_[0]) ? $_[0] : CORE::int($_[0]) - 1)) }
sub ceil { -floor(-$_[0]) }
sub fmod { $_[0] - CORE::int($_[0] / $_[1]) * $_[1] }
sub fabs { CORE::abs($_[0]) }
sub pow { $_[0] ** $_[1] }
sub asin { CORE::atan2($_[0], CORE::sqrt(1 - $_[0] * $_[0])) }
sub acos { CORE::atan2(CORE::sqrt(1 - $_[0] * $_[0]), $_[0]) }
sub atan { CORE::atan2($_[0], 1) }
sub tan { CORE::sin($_[0]) / CORE::cos($_[0]) }
sub sinh { (CORE::exp($_[0]) - CORE::exp(-$_[0])) / 2 }
sub cosh { (CORE::exp($_[0]) + CORE::exp(-$_[0])) / 2 }
sub tanh { sinh($_[0]) / cosh($_[0]) }
sub log10 { CORE::log($_[0]) / CORE::log(10) }
sub ldexp { $_[0] * (2 ** $_[1]) }
sub frexp {
    my $x = CORE::abs($_[0]);
    return (0, 0) if $x == 0;
    my $exp = 0;
    while ($x >= 1) { $x /= 2; $exp++ }
    while ($x < 0.5) { $x *= 2; $exp-- }
    return ($_[0] < 0 ? -$x : $x, $exp);
}
sub modf {
    my $int = CORE::int($_[0]);
    return ($_[0] - $int, $int);
}

# String error function
sub strerror { POSIX::_strerror(@_) }

# Signal functions
sub signal { POSIX::_signal(@_) }
sub raise { POSIX::_raise(@_) }
sub uname { POSIX::_uname(@_) }
sub sigprocmask { POSIX::_sigprocmask(@_) }

# Signal action - stub classes for JVM
sub sigaction {
    # On JVM, sigaction is a no-op stub
    return 0;
}

# SIG_BLOCK, SIG_UNBLOCK, SIG_SETMASK constants
use constant SIG_BLOCK   => 0;
use constant SIG_UNBLOCK => 1;
use constant SIG_SETMASK => 2;
use constant SIG_DFL     => 0;
use constant SIG_IGN     => 1;
use constant SIG_ERR     => -1;

# POSIX::SigSet - minimal stub for JVM
package POSIX::SigSet;
sub new {
    my $class = shift;
    return bless { signals => [@_] }, $class;
}
sub emptyset { $_[0]->{signals} = []; return 1; }
sub fillset  { return 1; }
sub addset   { push @{$_[0]->{signals}}, $_[1]; return 1; }
sub delset   { $_[0]->{signals} = [grep { $_ != $_[1] } @{$_[0]->{signals}}]; return 1; }
sub ismember { return grep { $_ == $_[1] } @{$_[0]->{signals}} ? 1 : 0; }

# POSIX::SigAction - minimal stub for JVM
package POSIX::SigAction;
sub new {
    my ($class, $handler, $sigset, $flags) = @_;
    return bless { handler => $handler, sigset => $sigset, flags => $flags || 0 }, $class;
}
sub handler { return $_[0]->{handler} }
sub mask    { return $_[0]->{sigset} }
sub flags   { return $_[0]->{flags} }

# POSIX::Termios - terminal I/O control
# The Java backend stores a native struct termios as an opaque byte string
# in the blessed hashref's "_data" key.  All field access goes through the
# Java POSIX module's termios_* methods.
package POSIX::Termios;

sub new {
    my $class = shift;
    my $data = POSIX::Termios::_new();
    return bless { _data => $data }, $class;
}

sub getattr {
    my ($self, $fd) = @_;
    $fd = fileno($fd) if ref $fd;
    $fd = 0 unless defined $fd;
    my @r = POSIX::Termios::_getattr($self->{_data}, $fd);
    return undef unless @r && defined $r[0];
    $self->{_data} = $r[0];
    return $r[1];  # "0 but true"
}

sub setattr {
    my ($self, $fd, $action) = @_;
    $fd = fileno($fd) if ref $fd;
    $fd = 0 unless defined $fd;
    $action = 0 unless defined $action;  # TCSANOW
    return POSIX::Termios::_setattr($self->{_data}, $fd, $action);
}

sub getiflag { return POSIX::Termios::_getiflag($_[0]->{_data}) }
sub getoflag { return POSIX::Termios::_getoflag($_[0]->{_data}) }
sub getcflag { return POSIX::Termios::_getcflag($_[0]->{_data}) }
sub getlflag { return POSIX::Termios::_getlflag($_[0]->{_data}) }

sub getcc {
    my ($self, $idx) = @_;
    return POSIX::Termios::_getcc($self->{_data}, $idx);
}

sub setiflag {
    my ($self, $val) = @_;
    my @r = POSIX::Termios::_setiflag($self->{_data}, $val);
    $self->{_data} = $r[0] if @r && defined $r[0];
    return $r[1];
}

sub setoflag {
    my ($self, $val) = @_;
    my @r = POSIX::Termios::_setoflag($self->{_data}, $val);
    $self->{_data} = $r[0] if @r && defined $r[0];
    return $r[1];
}

sub setcflag {
    my ($self, $val) = @_;
    my @r = POSIX::Termios::_setcflag($self->{_data}, $val);
    $self->{_data} = $r[0] if @r && defined $r[0];
    return $r[1];
}

sub setlflag {
    my ($self, $val) = @_;
    my @r = POSIX::Termios::_setlflag($self->{_data}, $val);
    $self->{_data} = $r[0] if @r && defined $r[0];
    return $r[1];
}

sub setcc {
    my ($self, $idx, $val) = @_;
    my @r = POSIX::Termios::_setcc($self->{_data}, $idx, $val);
    $self->{_data} = $r[0] if @r && defined $r[0];
    return $r[1];
}

sub getispeed { return POSIX::Termios::_getispeed($_[0]->{_data}) }
sub getospeed { return POSIX::Termios::_getospeed($_[0]->{_data}) }

sub setispeed {
    my ($self, $speed) = @_;
    my @r = POSIX::Termios::_setispeed($self->{_data}, $speed);
    $self->{_data} = $r[0] if @r && defined $r[0];
    return $r[1];
}

sub setospeed {
    my ($self, $speed) = @_;
    my @r = POSIX::Termios::_setospeed($self->{_data}, $speed);
    $self->{_data} = $r[0] if @r && defined $r[0];
    return $r[1];
}

package POSIX;

# Constants - generate subs for each constant that has Java implementation
# Note: O_* and WNOHANG/WUNTRACED are defined with 'use constant' above
for my $const (qw(
    EINTR ENOENT ESRCH EIO ENXIO E2BIG ENOEXEC EBADF ECHILD EAGAIN
    ENOMEM EACCES EFAULT ENOTBLK EBUSY EEXIST EXDEV ENODEV ENOTDIR
    EISDIR EINVAL ENFILE EMFILE ENOTTY ETXTBSY EFBIG ENOSPC ESPIPE
    EROFS EMLINK EPIPE EDOM ERANGE EPERM

    SEEK_SET SEEK_CUR SEEK_END

    F_OK R_OK W_OK X_OK

    F_DUPFD F_GETFD F_SETFD F_GETFL F_SETFL FD_CLOEXEC

    SIGHUP SIGINT SIGQUIT SIGILL SIGTRAP SIGABRT SIGBUS SIGFPE SIGKILL
    SIGUSR1 SIGSEGV SIGUSR2 SIGPIPE SIGALRM SIGTERM SIGCHLD SIGCONT
    SIGSTOP SIGTSTP

    TCSANOW TCSADRAIN TCSAFLUSH

    ECHO ECHOE ECHOK ECHONL ICANON IEXTEN ISIG
    BRKINT ICRNL INPCK ISTRIP IXON
    OPOST
    CS8 CSIZE PARENB
    VEOF VEOL VERASE VINTR VKILL VMIN VQUIT VSTART VSTOP VSUSP VTIME
)) {
    no strict 'refs';
    *{$const} = eval "sub () { POSIX::_const_$const() }";
}

# sysconf() variable name constants and stub implementation.
# Real POSIX sysconf() returns system-dependent runtime limits. PerlOnJava
# does not implement true sysconf(), but many CPAN modules (POE, Proc::Daemon,
# etc.) call sysconf(_SC_OPEN_MAX) etc. for sensible defaults. Provide the
# common _SC_* names as constants and have sysconf() return reasonable values.
BEGIN {
    my %sc = (
        _SC_ARG_MAX           => 0,
        _SC_CHILD_MAX         => 1,
        _SC_CLK_TCK           => 2,
        _SC_NGROUPS_MAX       => 3,
        _SC_OPEN_MAX          => 4,
        _SC_JOB_CONTROL       => 5,
        _SC_SAVED_IDS         => 6,
        _SC_VERSION           => 7,
        _SC_PAGESIZE          => 8,
        _SC_PAGE_SIZE         => 8,   # alias of _SC_PAGESIZE
        _SC_NPROCESSORS_CONF  => 9,
        _SC_NPROCESSORS_ONLN  => 10,
    );
    no strict 'refs';
    for my $name (keys %sc) {
        my $value = $sc{$name};
        *{"POSIX::$name"} = sub () { $value };
    }
}

sub sysconf {
    my $name = shift;
    return undef unless defined $name;
    if    ($name == 0)  { return 4096 * 1024; }   # _SC_ARG_MAX
    elsif ($name == 1)  { return 1024; }          # _SC_CHILD_MAX
    elsif ($name == 2)  { return 100; }           # _SC_CLK_TCK
    elsif ($name == 3)  { return 16; }            # _SC_NGROUPS_MAX
    elsif ($name == 4)  { return 1024; }          # _SC_OPEN_MAX
    elsif ($name == 5)  { return 1; }             # _SC_JOB_CONTROL
    elsif ($name == 6)  { return 1; }             # _SC_SAVED_IDS
    elsif ($name == 7)  { return 200809; }        # _SC_VERSION
    elsif ($name == 8)  { return 4096; }          # _SC_PAGESIZE
    elsif ($name == 9 || $name == 10) {           # _SC_NPROCESSORS_*
        my $n = eval { 0 + (`getconf _NPROCESSORS_ONLN 2>/dev/null` || 1) };
        $n = 1 if !$n || $n < 1;
        return $n;
    }
    return undef;
}

# Locale category constants - defined directly since XS _const_ may not exist
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
        *{"POSIX::$name"} = sub () { $lc{$name} };
    }
}

# Exit status macros
sub WIFEXITED { POSIX::_WIFEXITED(@_) }
sub WEXITSTATUS { POSIX::_WEXITSTATUS(@_) }
sub WIFSIGNALED { POSIX::_WIFSIGNALED(@_) }
sub WTERMSIG { POSIX::_WTERMSIG(@_) }
sub WIFSTOPPED { POSIX::_WIFSTOPPED(@_) }
sub WSTOPSIG { POSIX::_WSTOPSIG(@_) }
sub WCOREDUMP { POSIX::_WCOREDUMP(@_) }

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
