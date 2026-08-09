use strict;
use warnings;
use Test::More tests => 4;

ok('a' =~ /(?(?=a)a|b)/, 'positive assertion conditional takes yes branch');
ok('b' =~ /(?(?=a)a|b)/, 'positive assertion conditional takes no branch');
ok('a' =~ /(?(?!a)b|a)/, 'negative assertion conditional takes no branch');
ok('b' =~ /(?(?!a)b|a)/, 'negative assertion conditional takes yes branch');
