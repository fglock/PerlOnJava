use strict;
use warnings;
use Test::More;

ok('ab' =~ /a(*pla:b)b/, 'short positive lookahead alias');
ok('ab' =~ /a(*positive_lookahead:b)b/, 'long positive lookahead alias');
ok('ab' =~ /a(*plb:a)b/, 'short positive lookbehind alias');
ok('ab' =~ /a(*positive_lookbehind:a)b/, 'long positive lookbehind alias');
ok('ab' =~ /a(*nla:c)b/, 'short negative lookahead alias');
ok('ab' =~ /a(*negative_lookahead:c)b/, 'long negative lookahead alias');
ok('ab' =~ /a(*nlb:c)b/, 'short negative lookbehind alias');
ok('ab' =~ /a(*negative_lookbehind:c)b/, 'long negative lookbehind alias');

ok('abc' !~ /(*atomic:a|ab)c/, 'atomic alias prevents alternative retry');
ok('ab' =~ /a(*pla:(*nla:c)b)b/, 'nested alpha assertions');

my $captured = 'ab';
ok($captured =~ /a(*pla:(b))b/, 'capture inside alpha assertion participates');
is($1, 'b', 'alpha assertion publishes its capture');

ok('a' =~ /(?(*pla:a)a|b)/,
    'positive alpha assertion works as a conditional predicate');
ok('b' =~ /(?(*pla:a)a|b)/,
    'positive alpha assertion conditional takes its alternate');
ok('a' =~ /(?(*nla:a)b|a)/,
    'negative alpha assertion conditional takes its alternate');
ok('b' =~ /(?(*nla:a)b|a)/,
    'negative alpha assertion works as a conditional predicate');

for my $invalid (
    '(*positive_lookahead)',
    '(*positive_lookahead:a',
    '(*positive_lookaround:a)',
) {
    my $compiled = eval "qr/$invalid/";
    ok(!defined($compiled) && length($@), "malformed long alias is rejected: $invalid");
}

done_testing;
