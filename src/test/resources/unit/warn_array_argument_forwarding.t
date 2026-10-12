use strict;
use warnings;
use Test::More tests => 1;

my $warning;
{
    local $SIG{__WARN__} = sub { $warning = join '', @_ };
    sub forward_warning { warn @_ }
    forward_warning(1, 2);
}

like($warning, qr/^12(?: at .+ line \d+\.\n)?\z/,
    'warn concatenates every forwarded array argument');
