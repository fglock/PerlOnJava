use strict;
use warnings;
use Test::More;

my $value = '23';
$value += 0;

ok(builtin::created_as_number($value), 'numeric compound assignment creates a numeric channel');

done_testing;
