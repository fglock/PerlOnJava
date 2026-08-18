use strict;
use warnings;
use Test::More tests => 8;

$_ = 'xabcx';
ok(/(?<=(?=a)..)((?=c)|.)/g,
    'positive lookahead is allowed inside positive lookbehind');
is($1, '', 'first global match preserves the empty assertion capture');
ok(/(?<=(?=a)..)((?=c)|.)/g,
    'nested lookahead lookbehind remains usable for the next global match');
is($1, 'c', 'second global match preserves the consuming capture');

ok('abc' =~ /(?<=a(?!x)b)c/,
    'negative lookahead is allowed inside positive lookbehind');
ok('abc' =~ /(?<!x(?=b))c/,
    'positive lookahead is allowed inside negative lookbehind');
ok('abc' =~ /(?<!a(?!x))c/,
    'negative lookahead is allowed inside negative lookbehind');

ok('abc' =~ /(?<=(?=a)(ab))c/ && $1 eq 'ab',
    'capture state survives a nested lookahead in lookbehind');
