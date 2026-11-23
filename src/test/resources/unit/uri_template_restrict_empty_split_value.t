use strict;
use warnings;
use Test::More;
use URI::Escape qw(uri_unescape);

my ($name, $value) = split /=/, 'baz=';
is($name, 'baz', 'split extracts the variable name');
ok(defined $value, 'fixed-arity split preserves a present empty value');
is(uri_unescape($value), '', 'URI unescaping preserves the explicit empty value');

my ($omitted_name, $omitted_value) = split /=/, 'quux';
is($omitted_name, 'quux', 'split extracts an unassigned variable name');
ok(!defined $omitted_value, 'a missing value remains undef');

my @all_fields = split /=/, 'baz=';
is_deeply(\@all_fields, ['baz'], 'array assignment retains default trailing-empty removal');

my ($first, $rest) = split /,/, 'one,two,three';
is($rest, 'two', 'fixed-arity assignment discards fields beyond its target list');

done_testing;
