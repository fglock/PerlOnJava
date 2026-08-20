use strict;
use utf8;
use Test::More;
use threads;

my $g_subject = ('a' x 20_000) . 'b';
my $g_hits = 0;
for my $offset (1 .. 400) {
    pos($g_subject) = $offset;
    $g_hits++ if $g_subject =~ /\Gb/g;
}
is($g_hits, 0, '\\G rejection remains anchored at each global start');

my $anchored = 'abcdefg' x 30_000;
ok($anchored !~ /^XX\d{1,10}cde/,
   'absolute anchored required literal rejects a long subject');

my $implicit = ('0' x 40_000) . '::: 0c';
for my $pattern (
        qr/.*:::\s*ab/, qr/.*?:::\s*ab/,
        qr/.*:::\s*ab/i, qr/.*?:::\s*ab/i) {
    ok($implicit !~ $pattern,
       'greedy or lazy implicit anchor rejects missing required literal');
}

for my $character ("\N{CENT SIGN}", "\N{EURO SIGN}", "\N{GRINNING FACE}") {
    my $subject = ('0' x 1_000) . $character . 'x' . $character . 'ok';
    ok($subject =~ /.*${character}ok/,
       'UTF-8 literal keeps a later matching candidate after an earlier prefix');
    ok($subject !~ /.*${character}missing/,
       'UTF-8 literal rejects a missing required suffix');
}

ok("prefix\n:::ab" !~ /\A.*:::ab/,
   'non-multiline greedy star does not cross a newline');

my $global = ('0' x 1_000) . ('1' x 40_000);
my $matches = 0;
$matches++ while $global =~ /0/g;
is($matches, 1_000, 'long global scan preserves every match');

my @threads = map { threads->create(sub {
    my $subject = 'ab' x 10_000;
    utf8::upgrade($subject);
    my $count = 0;
    $count++ while $subject =~ /\Ga+ba+b/g && $count < 100;
    return $count;
}) } 1 .. 2;
is_deeply([ map { $_->join } @threads ], [100, 100],
          'two threaded global matchers preserve isolated anchored progress');

my ($callback_count, $nested_callback_count, $nested_match) = (0, 0, 0);
my $nested_callback = qr/x(?{ $nested_callback_count++ })y/;
ok('ab' =~ /a(?{
        $callback_count++;
        $nested_match = 'xy' =~ $nested_callback;
    })b/x,
   'callback reentry preserves the outer matcher stack');
is($callback_count, 1, 'reentrant callback executes exactly once');
ok($nested_match, 'nested callback matcher succeeds');
is($nested_callback_count, 1, 'nested callback executes exactly once');

my $nested_plain = qr/inn/;
my $dynamic_result = qr/b/;
ok('abc' =~ /a(??{
        'inner' =~ $nested_plain;
        $dynamic_result;
    })c/x,
   'dynamic continuation may run a nested matcher');

done_testing;
