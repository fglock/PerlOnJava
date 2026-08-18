use strict;
use warnings;
no warnings 'experimental::vlb';
use Test::More;

ok('ab' =~ /(?<=a(?=b))b/,
    'positive lookahead is admitted inside positive lookbehind');
ok('ac' =~ /(?<=a(?!b))c/,
    'negative lookahead is admitted inside positive lookbehind');
ok('ab' !~ /(?<=a(?!b))b/,
    'nested negative lookahead still rejects its forbidden suffix');

ok('ab' !~ /(?<!a(?=b))b/,
    'positive lookahead participates in negative lookbehind rejection');
ok('cb' =~ /(?<!a(?=b))b/,
    'negative lookbehind succeeds when nested positive lookahead path is absent');
ok('ac' !~ /(?<!a(?!b))c/,
    'negative lookahead participates in negative lookbehind rejection');
ok('bc' =~ /(?<!a(?!b))c/,
    'negative lookbehind succeeds when nested negative lookahead path is absent');

my $captured = 'xaaab';
ok($captured =~ /(?<=((?:a{1,3}))(?=(b)))b/,
    'bounded lookbehind backtracks with a nested capturing lookahead');
is($1, 'aaa', 'outer lookbehind capture publishes its longest successful text');
is($2, 'b', 'capture inside nested positive lookahead is published');
is_deeply([$-[0], $+[0]], [4, 5],
    'nested assertion preserves the consuming match span');

my $a254 = 'a' x 254;
ok(($a254 . 'b') =~ /(?<=a{254,255}(?=b))b/,
    'nested lookahead works at bounded lookbehind minimum 254');
is($-[0], 254, '254-character bounded lookbehind reports the right offset');

my $a255 = 'a' x 255;
ok(($a255 . 'b') =~ /(?<=a{254,255}(?=b))b/,
    'nested lookahead works at bounded lookbehind maximum 255');
is($-[0], 255, '255-character bounded lookbehind reports the right offset');

sub compile_error {
    my ($source) = @_;
    local $SIG{__WARN__} = sub {};
    eval "qr/$source/";
    return $@;
}

like(compile_error('(?<=a{255,256}(?=b))b'), qr/look-?behind/i,
    'bounded lookbehind above 255 remains rejected');
like(compile_error('(?<=a+(?=b))b'), qr/look-?behind/i,
    'unbounded lookbehind with nested lookahead remains rejected');
ok(length(compile_error('(?<=a(?=b)b')),
    'malformed nested assertion remains rejected');

done_testing;
