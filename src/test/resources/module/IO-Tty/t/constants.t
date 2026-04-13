#!perl

use strict;
use warnings;

use Test::More tests => 5;

# Test that IO::Tty exports constants via import()
{
    use IO::Tty qw(TIOCSCTTY TIOCNOTTY TCSETCTTY);

    # At least one of these should be defined on any POSIX system
    my $has_any = ( defined &TIOCSCTTY || defined &TIOCNOTTY || defined &TCSETCTTY );
    ok( $has_any, "at least one terminal ioctl constant is available" );
}

# Test that TIOCGWINSZ and TIOCSWINSZ are available (needed for winsize ops)
{
    use IO::Tty::Constant;

    my $get = eval { IO::Tty::Constant::TIOCGWINSZ() };
    ok( defined $get, "TIOCGWINSZ constant is available" );

    my $set = eval { IO::Tty::Constant::TIOCSWINSZ() };
    ok( defined $set, "TIOCSWINSZ constant is available" );
}

# Test CONFIG variable
{
    ok( defined $IO::Tty::CONFIG, "IO::Tty::CONFIG is defined" );
    like( $IO::Tty::CONFIG, qr/-D/, "CONFIG contains compile flags" );
}
