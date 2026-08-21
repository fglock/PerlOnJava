use strict;
use warnings;
use Test::More;
use threads;

for my $pattern (
    q{(?=a\K)}, q{(?!a\K)}, q{(?<=a\K)}, q{(?<!a\K)},
) {
    my $compiled = eval "qr/$pattern/";
    ok(!$compiled, "$pattern is rejected directly inside lookaround");
    like($@, qr/\\K not permitted in lookahead\/lookbehind/,
        "$pattern keeps its direct-use diagnostic");
}

my $called = qr/(?<x>a\K)(?=(?&x))/;
my $subject = 'aa';
ok($subject =~ $called, 'called KEEP matches inside positive lookahead');
ok(!defined($&), 'inverted whole-match text is undef');
is($-[0], 2, 'called KEEP preserves the inverted whole-match start');
is($+[0], 1, 'called KEEP preserves the lookahead end');
is($1, 'a', 'ordinary called-group text remains materialized');
is($-[1], 0, 'ordinary called-group start remains valid');
is($+[1], 1, 'ordinary called-group end remains valid');

my $defined = qr/(?(DEFINE)(?<x>a\K))(?=(?&x))/;
$subject = 'a';
ok($subject =~ $defined, 'DEFINE called KEEP matches inside positive lookahead');
ok(!defined($&), 'DEFINE inverted whole-match text is undef');
is($-[0], 1, 'DEFINE called KEEP preserves the inverted start');
is($+[0], 0, 'DEFINE lookahead preserves the zero-width end');

for my $case (
    [ positive_behind => 'a', qr/(?(DEFINE)(?<x>a\K))(?<=(?&x))/, 1, 1 ],
    [ negative_ahead  => 'b', qr/(?(DEFINE)(?<x>a\K))(?!(?&x))/,  0, 0 ],
    [ negative_behind => 'a', qr/(?(DEFINE)(?<x>a\K))(?<!(?&x))/, 0, 0 ],
) {
    my ($name, $input, $pattern, $start, $end) = @$case;
    ok($input =~ $pattern, "$name control matches");
    is($&, '', "$name keeps ordinary empty whole-match text");
    is($-[0], $start, "$name keeps its whole-match start");
    is($+[0], $end, "$name keeps its whole-match end");
}

my $replacement = 'aa';
is(($replacement =~ s/$called/X/), 1,
    'inverted called KEEP substitution reports one replacement');
is($replacement, 'aaXa',
    'inverted called KEEP substitution retains both published offsets');

$replacement = 'a';
is(($replacement =~ s/$defined/X/), 1,
    'DEFINE inverted KEEP substitution reports one replacement');
is($replacement, 'aXa',
    'DEFINE inverted KEEP substitution retains both published offsets');

'a' =~ /(a)(b)?/;
is($&, 'a', 'ordinary whole-match text remains materialized');
is($1, 'a', 'ordinary matched capture remains materialized');
ok(!defined($2), 'ordinary unmatched capture remains undef');

my @workers = map {
    threads->create(sub {
        for (1 .. 40) {
            my $input = 'aa';
            return 0 unless $input =~ $called;
            return 0 if defined($&);
            return 0 unless $-[0] == 2 && $+[0] == 1 && $1 eq 'a';
        }
        return 1;
    })
} 1 .. 2;
ok($_->join, 'thread reuse keeps inverted match text and offsets isolated')
    for @workers;

done_testing;
