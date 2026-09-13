use strict;
use warnings;
use Test::More tests => 6;

my $protected = qr/(?:(?:a(*SKIP)b|ac)|a(*COMMIT)b)|x(*THEN)y|a/;

ok !('aab' =~ $protected),
    'SKIP and COMMIT cut sibling trie continuations at the same start';
ok !('aabZ' =~ qr/(?:(?:(?:a(*SKIP)b|ac)|a(*COMMIT)b)Z)|x(*THEN)y|aZ/),
    'control cuts remain effective through a trailing literal';
ok 'ab' =~ $protected,
    'a successful protected control-verb path still matches';

ok !('aab' =~ qr/(?:(?:a(*THEN)b|ac)|a(*COMMIT)b)|x(*THEN)y|a/),
    'THEN retains the enclosing COMMIT continuation';

my $prune_count = 0;
'aaaabtz' =~ /a+(?{$prune_count++})(?:b|)(*PRUNE)(*FAIL)/;
is $prune_count, 4,
    'PRUNE still discards quantifier retries after a completed alternation';

my $skip_count = 0;
'aaabaaab' =~ /a+(?{$skip_count++})(?:b|)(*SKIP)(*FAIL)/;
is $skip_count, 2,
    'SKIP still advances past a completed alternation';
