use strict;
use warnings;
use Test::More tests => 4;

my $protected = qr/(?:(?:a(*SKIP)b|ac)|a(*COMMIT)b)|x(*THEN)y|a/;

ok !('aab' =~ $protected),
    'SKIP and COMMIT cut sibling trie continuations at the same start';
ok !('aabZ' =~ qr/(?:(?:(?:a(*SKIP)b|ac)|a(*COMMIT)b)Z)|x(*THEN)y|aZ/),
    'control cuts remain effective through a trailing literal';
ok 'ab' =~ $protected,
    'a successful protected control-verb path still matches';

ok !('aab' =~ qr/(?:(?:a(*THEN)b|ac)|a(*COMMIT)b)|x(*THEN)y|a/),
    'THEN retains the enclosing COMMIT continuation';
