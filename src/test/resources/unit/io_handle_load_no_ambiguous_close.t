use strict;
use warnings;
use Test::More tests => 2;

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    require IO::Handle;
}

is(scalar @warnings, 0, 'loading IO::Handle does not warn about ambiguous close calls');
ok($INC{'IO/Handle.pm'}, 'IO::Handle was loaded');
