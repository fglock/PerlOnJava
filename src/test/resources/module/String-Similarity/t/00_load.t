use strict;
use warnings;
use Test::More tests => 10;

use String::Similarity;

is(similarity('this should be the same', 'this should be the same'), 1, 'identical');
my $score = similarity('this should be same the', 'this should be the same');
ok($score > 0.825 && $score < 0.827, 'word transposition score');
is(similarity('A', 'B'), 0, 'different characters');
is(similarity("\x{456}", "\x{455}"), 0, 'different Unicode characters');

my $a = chr(169);
utf8::upgrade(my $b = $a);
is(similarity($a, $b), 1, 'upgraded and native strings match');

$b = "\x{0040}";
utf8::downgrade($a = $b);
is(similarity($a, $b), 1, 'downgraded and native strings match');

utf8::upgrade($a = chr(169));
use Encode qw(encode);
$b = encode 'utf-8', $a;
ok(similarity($a, $b) < 1, 'UTF-8 bytes differ from decoded text');

$a = [];
$b = [];
my $score1 = similarity($a, $b);
my $score2 = similarity($a, "$b");
ok(abs($score1 - $score2) < 0.001, 'references stringify consistently');

is(similarity('', undef), 1, 'undef stringifies as empty');
is(similarity('abc', 'axc', 0.5), similarity('abc', 'axc'), 'limit preserves valid score');
