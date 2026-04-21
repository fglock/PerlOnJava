#!perl

use strict;
use warnings;

use Test::More;
use IO::Pty;
require POSIX;

plan tests => 7;

# clone_winsize_from() copies terminal size from one tty to another.
# It croaks if the source is not a tty, and silently returns 1 if
# the destination is not a tty (e.g. master pty on some systems).

# Basic clone between two slave ttys
{
    my $pty1 = IO::Pty->new;
    my $pty2 = IO::Pty->new;
    my $slave1 = $pty1->slave;
    my $slave2 = $pty2->slave;

    SKIP: {
        skip "slave is not a tty on this system", 4
            unless POSIX::isatty($slave1) && POSIX::isatty($slave2);

        # Set a known size on slave1
        $slave1->set_winsize( 30, 90, 0, 0 );
        my @ws1 = $slave1->get_winsize();
        is( $ws1[0], 30, "source slave has row=30" );
        is( $ws1[1], 90, "source slave has col=90" );

        # Clone from slave1 to slave2
        my $ret = $slave2->clone_winsize_from($slave1);
        ok( $ret, "clone_winsize_from returns true on success" );

        my @ws2 = $slave2->get_winsize();
        is( $ws2[0], 30, "cloned row matches source" );
    }
}

# clone_winsize_from on master (not a tty on most systems) returns 1
{
    my $pty = IO::Pty->new;
    my $slave = $pty->slave;

    SKIP: {
        skip "slave is not a tty", 1 unless POSIX::isatty($slave);
        skip "master is a tty on this system (cannot test non-tty path)", 1
            if POSIX::isatty($pty);

        my $ret = $pty->clone_winsize_from($slave);
        is( $ret, 1, "clone_winsize_from on non-tty master returns 1" );
    }
}

# clone_winsize_from croaks when source is not a tty
{
    my $pty = IO::Pty->new;
    my $slave = $pty->slave;

    # Use a regular file as a non-tty source
    open my $fh, '<', $0 or die "Cannot open $0: $!";

    eval { $slave->clone_winsize_from($fh) };
    like( $@, qr/not a tty/i, "clone_winsize_from croaks on non-tty source" );
    close $fh;
}

# clone_winsize_from preserves pixel dimensions
{
    my $pty1 = IO::Pty->new;
    my $pty2 = IO::Pty->new;
    my $slave1 = $pty1->slave;
    my $slave2 = $pty2->slave;

    SKIP: {
        skip "slave is not a tty on this system", 1
            unless POSIX::isatty($slave1) && POSIX::isatty($slave2);

        $slave1->set_winsize( 25, 80, 640, 480 );
        $slave2->clone_winsize_from($slave1);
        my @ws = $slave2->get_winsize();
        is( $ws[0], 25, "cloned row with pixel values set" );
    }
}
