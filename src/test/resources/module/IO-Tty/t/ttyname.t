#!perl

use strict;
use warnings;

use Test::More tests => 5;
use IO::Pty;

# Test ttyname() on the master pty object
{
    my $pty = IO::Pty->new;
    ok( defined $pty, "IO::Pty created" );

    my $ttyname = $pty->ttyname;
    ok( defined $ttyname, "ttyname() returns a value" );
    like( $ttyname, qr{/dev/}, "ttyname() looks like a device path" );
}

# Test that slave ttyname matches what ttyname() returns
{
    my $pty = IO::Pty->new;
    my $ttyname = $pty->ttyname;
    my $slave = $pty->slave;
    ok( defined $slave, "got slave" );

    # The XS-level ttyname on the slave should match the stored name
    my $slave_ttyname = IO::Tty::ttyname($slave);
    is( $slave_ttyname, $ttyname,
        "XS ttyname() on slave matches Pty->ttyname()" );
}
