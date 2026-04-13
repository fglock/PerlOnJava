#!perl

use strict;
use warnings;

use Test::More tests => 5;

use IO::Pty;
require POSIX;

# Reliable fd-open check: POSIX::dup succeeds only on open fds.
sub fd_is_open {
    my ($fd) = @_;
    my $dup = POSIX::dup($fd);
    if ( defined $dup ) {
        POSIX::close($dup);
        return 1;
    }
    return 0;
}

# Test that destroying an IO::Pty object closes the slave fd
# when no external references exist.
# See https://github.com/toddr/IO-Tty/issues/14

{
    my $slave_fileno;
    {
        my $pty = IO::Pty->new;
        ok( defined $pty, "IO::Pty created" );
        $slave_fileno = $pty->slave->fileno;
    }
    # $pty is now out of scope and destroyed.
    # The slave fd should have been closed (no external refs).
    # TODO: PerlOnJava fdopen does not close underlying native fd on DESTROY yet
    TODO: {
        local $TODO = "PerlOnJava: fdopen/close does not close native fd yet";
        ok( !fd_is_open($slave_fileno),
            "slave fd $slave_fileno closed after IO::Pty destruction (no external refs)" );
    }
}

# Test that destroying IO::Pty does NOT close the slave fd
# when an external reference exists (e.g. IPC::Run scenario).
# See https://github.com/toddr/IO-Tty/issues/62

{
    my $slave;
    my $slave_fileno;
    {
        my $pty = IO::Pty->new;
        ok( defined $pty, "IO::Pty created for external-ref test" );
        $slave = $pty->slave;
        $slave_fileno = $slave->fileno;
    }
    # $pty destroyed, but $slave still holds a reference.
    # The slave fd must remain open.
    ok( fd_is_open($slave_fileno),
        "slave fd $slave_fileno stays open when external ref exists (GH #62)" );
    close $slave;
    # TODO: PerlOnJava fdopen does not close underlying native fd on close yet
    TODO: {
        local $TODO = "PerlOnJava: fdopen/close does not close native fd yet";
        ok( !fd_is_open($slave_fileno),
            "slave fd $slave_fileno closed after explicit close" );
    }
}
