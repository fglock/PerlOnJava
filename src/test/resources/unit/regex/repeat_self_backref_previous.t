use strict;
use warnings;
use Test::More tests => 8;

ok('aaaaaaaaaa' =~ /^(a\1?){4}$/,
    'optional self-backref sees the prior repeat iteration');
is($1, 'aaaa', 'optional self-backref publishes the final capture');

ok('aaaaaaaaaa' =~ /^(a(?(1)\1)){4}$/,
    'conditional self-backref sees the prior repeat iteration');
is($1, 'aaaa', 'conditional self-backref publishes the final capture');

ok('aaaaaa' =~ /^(a\1?){4}$/,
    'optional self-backref can consume a shorter prior capture');
is($1, 'aa', 'shorter self-backref publishes the final capture');

my $simple_alt = 'xa=xaaa' =~ /^(xa|=?\1a){2}$/;
my $nested_alt = 'xa=xaaa' =~ /^(xa|(?:=|zzzz|)\1a){2}$/;
if ($] < 5.037010) {
    ok($simple_alt, 'legacy Perl retains self-capture across an alternative');
    ok($nested_alt, 'legacy Perl retains self-capture across nested alternatives');
}
else {
    ok(!$simple_alt, 'ordinary alternative invalidates the prior self-capture');
    ok(!$nested_alt, 'nested alternative keeps the prior self-capture invalid');
}
