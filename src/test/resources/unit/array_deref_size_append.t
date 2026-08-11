use strict;
use warnings;
use Test::More;

my $values = [];
$values->[@$values] = 'first';
$values->[@$values] = 'second';

is_deeply($values, ['first', 'second'],
    'dereferenced array size is the next append index');
is(scalar(@$values), 2, 'dereferenced array has the expected size');

my $probed = [];
my $missing = $probed->[@$probed];
is(scalar(@$probed), 0, 'fetching the next array slot does not extend the array');
$probed->[@$probed] = 'value';
is_deeply($probed, ['value'], 'append after a missing-slot fetch has no hole');

sub inspect_missing { defined $_[0] }
sub inspect_missing_scalar ($) { defined $_[0] }
sub is_string_like {
    no warnings 'uninitialized';
    return !ref($_[0]) && ref(\$_[0]) ne 'GLOB' && length($_[0]) > 0;
}
my $argument = [];
ok(!inspect_missing($argument->[@$argument]), 'missing array element is undef as an argument');
is(scalar(@$argument), 0, 'passing a missing array element does not extend the array');

my $prototyped_argument = [];
ok(!inspect_missing_scalar($prototyped_argument->[@$prototyped_argument]),
    'missing array element is undef for a scalar-prototype argument');
is(scalar(@$prototyped_argument), 0,
    'scalar-prototype argument does not extend a missing array element');

my $referenced_argument = [];
ok(!is_string_like($referenced_argument->[@$referenced_argument]),
    'string predicate rejects a missing array element');
is(scalar(@$referenced_argument), 1,
    'taking a reference to an aliased missing argument extends its source array');

done_testing;
