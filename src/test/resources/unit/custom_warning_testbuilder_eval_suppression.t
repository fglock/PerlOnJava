use strict;
use warnings;
use Test::More;

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    local $TODO = 'an undefined value is intentionally diagnosed by like';
    like(undef, qr/is an integer/, 'undefined TODO value does not match');
}

is(scalar(@warnings), 0,
    'Test::Builder suppresses the redundant uninitialized match warning');

done_testing;
