use strict;
use warnings;
use Test::More;

ok('alphabet' =~ /pha/, 'literal');
ok('green' =~ /red|green|blue/, 'alternation');

my $captured = 'name=perl';
ok($captured =~ /name=(?<language>\w+)/, 'numbered and named capture match');
is($1, 'perl', 'numbered capture value');
is($+{language}, 'perl', 'named capture value');

ok("A\x{3b1}9" =~ /[A-Z]\p{Greek}\d/, 'class and Unicode property');
ok("first\nsecond" =~ /^second$/m, 'multiline anchors');
ok("a\nlevel\nz" =~ /a.*z/s, 'dot-all modifier');
ok('item42' =~ /item\d{2}/, 'bounded quantifier');
ok('colour' =~ /colou?r/, 'optional quantifier');

ok('foobar' =~ /foo(?=bar)/, 'positive lookahead');
ok('foobar' =~ /(?<=foo)bar/, 'fixed positive lookbehind');
ok('foobaz' =~ /foo(?!bar)/, 'negative lookahead');

my $substitution = 'red green red';
my $replacements = ($substitution =~ s/red/blue/g);
is($replacements, 2, 'global substitution count');
is($substitution, 'blue green blue', 'global substitution result');

my $word = qr/[a-z]+/i;
ok('Perl' =~ $word, 'qr reuse');
my $suffix = qr/\d+/;
ok('item42' =~ /item$suffix/, 'qr interpolation');

my @global = ('a1b22c333' =~ /(\d+)/g);
is_deeply(\@global, [qw(1 22 333)], 'global match list');

my $continued = '12ab';
pos($continued) = 0;
ok($continued =~ /\d+/gc, '/gc first match');
is(pos($continued), 2, '/gc advances pos');
ok(!($continued =~ /\d+/gc), '/gc failed continuation');
is(pos($continued), 2, '/c preserves pos after failure');

my $once_text = 'first';
my $once_pattern = 'first';
sub once_matches {
    my ($candidate) = @_;
    return $candidate =~ /$once_pattern/o;
}
ok(once_matches($once_text), '/o initial compilation');
$once_pattern = 'second';
ok(once_matches('first'), '/o reuses the initial pattern');

my $byte = pack('C', 0xe9);
utf8::downgrade($byte, 1);
my $unicode = "\x{e9}";
utf8::upgrade($unicode);
ok($byte =~ /\x{e9}/, 'byte scalar match');
ok($unicode =~ /\x{e9}/u, 'Unicode scalar match');

ok('MiXeD' =~ /mixed/i, 'case-insensitive modifier');
ok('a b' =~ /a \s+ b/x, 'extended modifier');
ok('ab' =~ /(?:a)(b)/n, 'non-capturing-default modifier');
is($1, undef, '/n suppresses unnamed captures');

done_testing;
