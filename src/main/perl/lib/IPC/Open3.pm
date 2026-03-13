package IPC::Open3;

use strict;
use warnings;

use Exporter 'import';
use Carp;
use Symbol qw(gensym qualify);

our $VERSION = '1.24';
our @EXPORT = qw(open3);

# Load the Java XS implementation via XSLoader
require XSLoader;
XSLoader::load('IPC::Open3', $VERSION);

=head1 NAME

IPC::Open3 - open a process for reading, writing, and error handling using open3()

=head1 SYNOPSIS

    use IPC::Open3;
    use Symbol 'gensym';

    my $pid = open3(my $chld_in, my $chld_out, my $chld_err = gensym,
                    'some', 'cmd', 'and', 'args');

    # reap zombie and retrieve exit status
    waitpid( $pid, 0 );
    my $child_exit_status = $? >> 8;

=head1 DESCRIPTION

This is the PerlOnJava implementation of IPC::Open3 using Java's ProcessBuilder.

=cut

sub open3 {
    my ($wtr, $rdr, $err, @cmd) = @_;

    # Validate we have a command
    croak "open3: no command specified" unless @cmd;

    # Handle the case where a single command string needs shell interpretation
    # vs multiple args which are passed directly

    # Set up handles - create globs if needed
    my $wtr_ref = \$_[0];
    my $rdr_ref = \$_[1];
    my $err_ref = \$_[2];

    # Call the XS implementation
    my $pid = _open3($wtr_ref, $rdr_ref, $err_ref, @cmd);

    # Update the caller's variables
    $_[0] = $$wtr_ref;
    $_[1] = $$rdr_ref;
    $_[2] = $$err_ref if defined $err;

    # Turn on autoflush for the write handle
    if (defined $_[0]) {
        my $old = select($_[0]);
        $| = 1;
        select($old);
    }

    return $pid;
}

1;

__END__

=head1 SEE ALSO

L<IPC::Open2>

=cut
