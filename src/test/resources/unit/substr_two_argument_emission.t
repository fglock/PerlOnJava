use strict;
use warnings;
use utf8;
use Test::More;

my $ascii = 'abcdef';
is(substr($ascii, 2), 'cdef', 'two-argument substr returns the suffix');
substr($ascii, 2) = 'XYZ';
is($ascii, 'abXYZ', 'two-argument substr remains an assignable lvalue');

my $unicode = "A\x{1F600}BC";
is(substr($unicode, 1), "\x{1F600}BC",
    'two-argument substr counts a supplementary character once');

my $snapshot_source = '1234';
my $snapshot = substr($snapshot_source, 1);
$snapshot_source = '5678';
is($snapshot, '234', 'two-argument scalar assignment retains the initial snapshot');

{
    no warnings 'substr';
    my $outside = substr('abc', 99);
    ok(!defined $outside, 'two-argument out-of-range read remains undef');
}

done_testing;
