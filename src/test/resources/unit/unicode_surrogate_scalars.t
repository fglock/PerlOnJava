#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

use Unicode::Collate;

no warnings 'utf8';

sub codepoints {
    return join ",", map { sprintf "%X", $_ } unpack("U*", $_[0]);
}

my $surrogate_pair_scalars = "\x{D9AB}\x{DFFF}";
is(length($surrogate_pair_scalars), 2, "adjacent surrogate scalars stay separate");
is(codepoints($surrogate_pair_scalars), "D9AB,DFFF", "unpack U preserves adjacent surrogate scalars");

my $supplementary_scalar = "\x{7AFFF}";
is(length($supplementary_scalar), 1, "supplementary scalar remains one Perl character");
is(codepoints($supplementary_scalar), "7AFFF", "unpack U preserves supplementary scalar");

my $collator = Unicode::Collate->new(
    table => 'keys.txt',
    level => 1,
    normalization => undef,
    UCA_Version => 8,
);

for my $ret ("Pe\x{D9AB}\x{DFFF}", "Pe\x{300}\x{D800}\x{DFFF}") {
    my ($match) = $collator->match($ret . "rl", "pe");
    is(codepoints($match), codepoints($ret), "Unicode::Collate match preserves illegal surrogate scalars");
    is(length($match), length($ret), "Unicode::Collate match length includes illegal surrogate scalars");
}

done_testing;
