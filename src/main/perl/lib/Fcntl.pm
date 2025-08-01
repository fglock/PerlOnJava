package Fcntl;

use strict;
use warnings;

require Exporter;
our @ISA = qw(Exporter);

# File access modes
use constant O_RDONLY   => 0;      # Open for reading only
use constant O_WRONLY   => 1;      # Open for writing only
use constant O_RDWR     => 2;      # Open for reading and writing

# File creation flags
use constant O_CREAT    => 0100;   # Create file if it doesn't exist
use constant O_EXCL     => 0200;   # Exclusive use (fail if file exists with O_CREAT)
use constant O_NOCTTY   => 0400;   # Don't make terminal the controlling terminal
use constant O_TRUNC    => 01000;  # Truncate file to zero length
use constant O_APPEND   => 02000;  # Append mode - writes always go to end of file
use constant O_NONBLOCK => 04000;  # Non-blocking mode
use constant O_NDELAY   => O_NONBLOCK;  # Synonym for O_NONBLOCK

# Seek constants
use constant SEEK_SET   => 0;      # Seek from beginning of file
use constant SEEK_CUR   => 1;      # Seek from current position
use constant SEEK_END   => 2;      # Seek from end of file

# Flock constants
use constant LOCK_SH    => 1;      # Shared lock
use constant LOCK_EX    => 2;      # Exclusive lock
use constant LOCK_NB    => 4;      # Non-blocking lock
use constant LOCK_UN    => 8;      # Unlock

# File test constants (for fcntl)
use constant F_DUPFD    => 0;      # Duplicate file descriptor
use constant F_GETFD    => 1;      # Get file descriptor flags
use constant F_SETFD    => 2;      # Set file descriptor flags
use constant F_GETFL    => 3;      # Get file status flags
use constant F_SETFL    => 4;      # Set file status flags
use constant F_GETLK    => 5;      # Get record locking info
use constant F_SETLK    => 6;      # Set record locking info
use constant F_SETLKW   => 7;      # Set record locking info (wait)

# Close-on-exec flag
use constant FD_CLOEXEC => 1;      # Close file descriptor on exec

# Compatibility with standard Fcntl module
# These are commonly used combinations
use constant O_RDONLY_EXCL => O_RDONLY | O_EXCL;
use constant O_WRONLY_CREAT => O_WRONLY | O_CREAT;
use constant O_WRONLY_CREAT_EXCL => O_WRONLY | O_CREAT | O_EXCL;
use constant O_WRONLY_CREAT_TRUNC => O_WRONLY | O_CREAT | O_TRUNC;
use constant O_RDWR_CREAT => O_RDWR | O_CREAT;


# Named groups of exports
our %EXPORT_TAGS = (
    'flock'   => [qw(LOCK_SH LOCK_EX LOCK_NB LOCK_UN)],
    'Fcompat' => [qw(FAPPEND FASYNC FCREAT FDEFER FDSYNC FEXCL FLARGEFILE
		     FNDELAY FNONBLOCK FRSYNC FSYNC FTRUNC)],
    'seek'    => [qw(SEEK_SET SEEK_CUR SEEK_END)],
    'mode'    => [qw(S_ISUID S_ISGID S_ISVTX S_ISTXT
		     _S_IFMT S_IFREG S_IFDIR S_IFLNK
		     S_IFSOCK S_IFBLK S_IFCHR S_IFIFO S_IFWHT S_ENFMT
		     S_IRUSR S_IWUSR S_IXUSR S_IRWXU
		     S_IRGRP S_IWGRP S_IXGRP S_IRWXG
		     S_IROTH S_IWOTH S_IXOTH S_IRWXO
		     S_IREAD S_IWRITE S_IEXEC
		     S_ISREG S_ISDIR S_ISLNK S_ISSOCK
		     S_ISBLK S_ISCHR S_ISFIFO
		     S_ISWHT S_ISENFMT
		     S_IFMT S_IMODE
                  )],
);

# Items to export into callers namespace by default
# (move infrequently used names to @EXPORT_OK below)
our @EXPORT =
  qw(
	FD_CLOEXEC
	F_ALLOCSP
	F_ALLOCSP64
	F_COMPAT
	F_DUP2FD
	F_DUPFD
	F_EXLCK
	F_FREESP
	F_FREESP64
	F_FSYNC
	F_FSYNC64
	F_GETFD
	F_GETFL
	F_GETLK
	F_GETLK64
	F_GETOWN
	F_NODNY
	F_POSIX
	F_RDACC
	F_RDDNY
	F_RDLCK
	F_RWACC
	F_RWDNY
	F_SETFD
	F_SETFL
	F_SETLK
	F_SETLK64
	F_SETLKW
	F_SETLKW64
	F_SETOWN
	F_SHARE
	F_SHLCK
	F_UNLCK
	F_UNSHARE
	F_WRACC
	F_WRDNY
	F_WRLCK
	O_ACCMODE
	O_ALIAS
	O_APPEND
	O_ASYNC
	O_BINARY
	O_CREAT
	O_DEFER
	O_DIRECT
	O_DIRECTORY
	O_DSYNC
	O_EXCL
	O_EXLOCK
	O_LARGEFILE
	O_NDELAY
	O_NOCTTY
	O_NOFOLLOW
	O_NOINHERIT
	O_NONBLOCK
	O_RANDOM
	O_RAW
	O_RDONLY
	O_RDWR
	O_RSRC
	O_RSYNC
	O_SEQUENTIAL
	O_SHLOCK
	O_SYNC
	O_TEMPORARY
	O_TEXT
	O_TRUNC
	O_WRONLY
     );

# Other items we are prepared to export if requested
our @EXPORT_OK = (qw(
	DN_ACCESS
	DN_ATTRIB
	DN_CREATE
	DN_DELETE
	DN_MODIFY
	DN_MULTISHOT
	DN_RENAME
	F_ADD_SEALS
	F_GETLEASE
	F_GETPIPE_SZ
	F_GET_SEALS
	F_GETSIG
	F_NOTIFY
	F_SEAL_FUTURE_WRITE
	F_SEAL_GROW
	F_SEAL_SEAL
	F_SEAL_SHRINK
	F_SEAL_WRITE
	F_SETLEASE
	F_SETPIPE_SZ
	F_SETSIG
	LOCK_MAND
	LOCK_READ
	LOCK_RW
	LOCK_WRITE
        O_ALT_IO
        O_EVTONLY
	O_IGNORE_CTTY
	O_NOATIME
	O_NOLINK
        O_NOSIGPIPE
	O_NOTRANS
        O_SYMLINK
        O_TMPFILE
        O_TTY_INIT
), map {@{$_}} values %EXPORT_TAGS);

1;

__END__

=head1 NAME

Fcntl - File control options

=head1 SYNOPSIS

    use Fcntl;                     # Import nothing
    use Fcntl ':mode';             # Import file access modes
    use Fcntl ':seek';             # Import seek constants
    use Fcntl ':flock';            # Import file locking constants
    use Fcntl qw(:mode :seek);     # Import multiple tags
    use Fcntl ':all';              # Import everything

    # Examples
    use Fcntl qw(SEEK_SET SEEK_CUR SEEK_END);
    seek($fh, 0, SEEK_SET);        # Seek to beginning
    seek($fh, 10, SEEK_CUR);       # Seek forward 10 bytes
    seek($fh, -10, SEEK_END);      # Seek to 10 bytes before end

=head1 DESCRIPTION

This module provides file control constants for use with Perl's built-in
functions like C<seek>, C<sysopen>, and C<flock>.

=head2 File Access Modes

=over 4

=item O_RDONLY

Open for reading only.

=item O_WRONLY

Open for writing only.

=item O_RDWR

Open for reading and writing.

=back

=head2 File Creation Flags

=over 4

=item O_CREAT

Create the file if it doesn't exist.

=item O_EXCL

When used with O_CREAT, fail if the file already exists.

=item O_TRUNC

Truncate the file to zero length if it exists.

=item O_APPEND

Open in append mode. All writes go to the end of the file.

=back

=head2 Seek Constants

=over 4

=item SEEK_SET

Position relative to the beginning of the file.

=item SEEK_CUR

Position relative to the current position.

=item SEEK_END

Position relative to the end of the file.

=back

=head2 File Locking Constants

=over 4

=item LOCK_SH

Shared lock (for reading).

=item LOCK_EX

Exclusive lock (for writing).

=item LOCK_NB

Non-blocking lock (can be OR'ed with LOCK_SH or LOCK_EX).

=item LOCK_UN

Unlock.

=back

=head1 EXAMPLES

    # Open a file for writing, create if doesn't exist, truncate if does
    use Fcntl qw(O_WRONLY O_CREAT O_TRUNC);
    sysopen(my $fh, "file.txt", O_WRONLY | O_CREAT | O_TRUNC);

    # Seek to end of file
    use Fcntl qw(SEEK_END);
    seek($fh, 0, SEEK_END);

    # Get an exclusive lock
    use Fcntl qw(LOCK_EX);
    flock($fh, LOCK_EX);

=head1 SEE ALSO

L<perlfunc/seek>, L<perlfunc/sysopen>, L<perlfunc/flock>

=cut
