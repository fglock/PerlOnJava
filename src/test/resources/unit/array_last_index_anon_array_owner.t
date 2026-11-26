use strict;
use warnings;
use Test::More tests => 3;

my $last_index = \$#{[]};

is($$last_index, undef,
    'an anonymous array is orphaned after its statement');

my @warnings;
{
    local $^W = 1;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    $$last_index = 1000;
}

is(scalar @warnings, 1,
    'writing the temporary array length warns after the array is freed');
like($warnings[0], qr/^Attempt to set length of freed array/,
    'the freed anonymous array reports the expected warning');
