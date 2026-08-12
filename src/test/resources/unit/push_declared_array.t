use strict;
use warnings;
use Test::More tests => 4;

push my @pushed, 'first';
push @pushed, 'second';
is_deeply(\@pushed, [qw(first second)],
    'push accepts a newly declared lexical array');

unshift my @unshifted, 'last';
unshift @unshifted, 'first';
is_deeply(\@unshifted, [qw(first last)],
    'unshift accepts a newly declared lexical array');

my $arrayref = [];
push @$arrayref, 1, 2;
is_deeply($arrayref, [1, 2], 'push still accepts an array dereference');

our @package_array;
push @package_array, 3;
is_deeply(\@package_array, [3], 'push still accepts a package array');
