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

# Export lists
our @EXPORT = qw();  # Nothing exported by default

our @EXPORT_OK = qw(
    O_RDONLY O_WRONLY O_RDWR
    O_CREAT O_EXCL O_NOCTTY O_TRUNC O_APPEND O_NONBLOCK O_NDELAY
    SEEK_SET SEEK_CUR SEEK_END
    LOCK_SH LOCK_EX LOCK_NB LOCK_UN
    F_DUPFD F_GETFD F_SETFD F_GETFL F_SETFL F_GETLK F_SETLK F_SETLKW
    FD_CLOEXEC
);

our %EXPORT_TAGS = (
    'mode'   => [qw(O_RDONLY O_WRONLY O_RDWR)],
    'open'   => [qw(O_CREAT O_EXCL O_NOCTTY O_TRUNC O_APPEND O_NONBLOCK O_NDELAY)],
    'seek'   => [qw(SEEK_SET SEEK_CUR SEEK_END)],
    'flock'  => [qw(LOCK_SH LOCK_EX LOCK_NB LOCK_UN)],
    'fcntl'  => [qw(F_DUPFD F_GETFD F_SETFD F_GETFL F_SETFL F_GETLK F_SETLK F_SETLKW FD_CLOEXEC)],
    'all'    => [@EXPORT_OK],
);

# Compatibility with standard Fcntl module
# These are commonly used combinations
use constant O_RDONLY_EXCL => O_RDONLY | O_EXCL;
use constant O_WRONLY_CREAT => O_WRONLY | O_CREAT;
use constant O_WRONLY_CREAT_EXCL => O_WRONLY | O_CREAT | O_EXCL;
use constant O_WRONLY_CREAT_TRUNC => O_WRONLY | O_CREAT | O_TRUNC;
use constant O_RDWR_CREAT => O_RDWR | O_CREAT;

push @EXPORT_OK, qw(
    O_RDONLY_EXCL O_WRONLY_CREAT O_WRONLY_CREAT_EXCL
    O_WRONLY_CREAT_TRUNC O_RDWR_CREAT
);

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