use strict;
use warnings;
use Test::More tests => 2;

my @warnings;
my $pattern;
{
    no warnings 'regexp';
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    $pattern = qr/(.0\N{6,0}0\N{6,0}000000000000000000000000000000000)/;
}

is(scalar(@warnings), 0,
    q{no warnings 'regexp' suppresses descending plain-N interval warnings});
ok('' !~ $pattern,
    'descending plain-N intervals compile as an impossible pattern');
