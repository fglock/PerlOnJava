use strict;
use warnings;
use Test::More;

my $outer_separator = $/;
{
    local (@ARGV, $/) = ('fixture.txt', "\n");
    is_deeply(\@ARGV, ['fixture.txt', "\n"], 'nonterminal array receives the full RHS list');
    ok(!defined $/, 'scalar following a nonterminal array receives undef');
}

is($/, $outer_separator, 'localized scalar is restored');

done_testing;
