#!perl

use strict;
use warnings;

use Test::More;
use IO::Pty;
use POSIX;

plan tests => 7;

my $master = IO::Pty->new;
ok( $master, "IO::Pty->new succeeded" );

my $slave = $master->slave;
ok( $slave, "got slave" );

ok( POSIX::isatty($slave), "slave is a tty" );

my $ret = $slave->set_raw();
ok( $ret, "set_raw() returned success" );

# verify termios flags match cfmakeraw expectations
my $ttyno = fileno($slave);
my $termios = POSIX::Termios->new;
ok( $termios->getattr($ttyno), "getattr after set_raw" );

my $lflag = $termios->getlflag();
is( $lflag & POSIX::ECHO(),   0, "ECHO is off after set_raw" );
is( $lflag & POSIX::ICANON(), 0, "ICANON is off after set_raw" );
