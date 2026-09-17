use v5.18;
use strict;
use warnings;
use feature 'lexical_subs';
no warnings 'experimental::lexical_subs';
use Test::More;

my $value = 43;
my sub lexical_constant_looking_closure :prototype() { $value }

is lexical_constant_looking_closure, 43,
    'a lexical named sub with a no-argument prototype retains its closure';

done_testing;
