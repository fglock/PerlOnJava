package IPC::Open2;

use strict;
use warnings;

use Exporter 'import';
use Carp;

our $VERSION = '1.08';
our @EXPORT = qw(open2);

# Load IPC::Open3 which contains both _open2 and _open3 XS implementations
use IPC::Open3 ();

=head1 NAME

IPC::Open2 - open a process for both reading and writing using open2()

=head1 SYNOPSIS

    use IPC::Open2;

    my $pid = open2(my $chld_out, my $chld_in,
                    'some', 'cmd', 'and', 'args');

    # reap zombie and retrieve exit status
    waitpid( $pid, 0 );
    my $child_exit_status = $? >> 8;

=head1 DESCRIPTION

This is the PerlOnJava implementation of IPC::Open2 using Java's ProcessBuilder.
Child's stderr goes to the parent's stderr.

=cut

sub open2 {
    my ($rdr, $wtr, @cmd) = @_;

    # Validate we have a command
    croak "open2: no command specified" unless @cmd;

    # Set up handles
    my $rdr_ref = \$_[0];
    my $wtr_ref = \$_[1];

    # Call the XS implementation (in IPC::Open3 package)
    my $pid = IPC::Open3::_open2($rdr_ref, $wtr_ref, @cmd);

    # Update the caller's variables
    $_[0] = $$rdr_ref;
    $_[1] = $$wtr_ref;

    # Turn on autoflush for the write handle
    if (defined $_[1]) {
        my $old = select($_[1]);
        $| = 1;
        select($old);
    }

    return $pid;
}

1;

__END__

=head1 SEE ALSO

L<IPC::Open3>

=cut
