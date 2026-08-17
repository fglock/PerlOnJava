use strict;
use warnings;
use utf8;
use Test::More tests => 20;

ok('abc' =~ /b/, 'literal search');
is('abc' =~ /(b)/ ? $1 : undef, 'b', 'numbered capture');
is('abc' =~ /(?<middle>b)/ ? $+{middle} : undef, 'b', 'named capture');
ok('AbC' =~ /abc/i, 'case-insensitive modifier');
ok("x\ny" =~ /^y/m, 'multiline modifier');
ok("x\ny" =~ /x.y/s, 'dot-all modifier');
ok('ab' =~ /a \s* b/x, 'extended modifier');
ok('abc' =~ /a(?:b|x)c/, 'alternation and noncapturing group');
ok('aaab' =~ /a{2,3}b/, 'bounded quantifier');
ok('word' =~ /\Aword\z/, 'absolute anchors');
ok('xwordy' =~ /(?<=x)word(?=y)/, 'lookaround');
ok('é' =~ /\w/u, 'Unicode word character');
ok('A' =~ /\p{Lu}/u, 'Unicode property');

my $global = 'a1b22';
my @digits = ($global =~ /(\d+)/g);
is_deeply(\@digits, [qw(1 22)], 'global list capture');

my $positioned = 'abcabc';
pos($positioned) = 3;
ok($positioned =~ /\Gabc/g, '\G honors pos');
is(pos($positioned), 6, 'global match updates pos');

my $continued = 'abc';
ok($continued =~ /a/gc, '/gc finds first token');
ok($continued !~ /z/gc && pos($continued) == 1, '/c preserves pos on failure');

my $replacement = 'abcabc';
$replacement =~ s/b/X/g;
is($replacement, 'aXcaXc', 'global substitution');

my $zero = 'ab';
my @positions;
push @positions, pos($zero) while $zero =~ /(?=.)/g;
is_deeply(\@positions, [0, 1], 'zero-width global search advances');
