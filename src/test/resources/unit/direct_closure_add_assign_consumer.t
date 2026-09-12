use strict;
use warnings;
use Test::More;

# The selected shape is an ordinary scalar += consuming a zero-argument
# captured-integer closure.  These are Perl-level contracts, not path probes.
my ($left, $right) = (17, 25);
my $sum = sub { $left + $right };
my $target = 3;
$target += $sum->();
is($target, 45, 'ordinary target receives a direct closure sum');

$right = 100;
$target += $sum->();
is($target, 162, 'current captured values are used for each call');

# A string capture requires normal numeric conversion rather than the native
# integer transfer path.
$left = '010';
$target += $sum->();
is($target, 272, 'string capture falls back to ordinary numeric addition');

# Overflow must retain Perl's existing promotion behavior.
my $one = 1;
my $increment = sub { $one };
my $wide = 9_223_372_036_854_775_807;
$wide += $increment->();
is("$wide", '9223372036854775808', 'overflow promotes through the ordinary path');

done_testing;
