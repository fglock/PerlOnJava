use strict;
use warnings;
use Test::More tests => 5;

ok('aaabc' =~ /(?<=a{1,3}b)c/,
    'bounded quantifier works inside a lookbehind sequence');
ok('aaabc' !~ /(?<!a{1,3}b)c/,
    'compound negative lookbehind checks every finite length');
ok('zc' =~ /(?<!a{1,3}b)c/,
    'compound negative lookbehind accepts an unrelated prefix');
ok('xbcbcz' =~ /(?<=x(?:a|bc){1,2})z/,
    'nested finite alternation contributes a bounded range');
ok('abcd' =~ /(?<=ab?c)d/,
    'optional terms work inside a compound lookbehind');
