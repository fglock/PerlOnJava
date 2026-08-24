use strict;
use warnings;
use Test::More tests => 20;

our $count;
my $length = 10;
my $subject = 'a' x $length;

$count = 0;
my $matched = $subject =~ /(.*)(?{ $count++ })[bc]/;
ok(!$matched, 'ordinary callback failure remains a failed match');
is($count, $length * ($length + 3) / 2 + 1,
    'ordinary callback disables the trailing-class optimization');

$count = 0;
$matched = $subject =~ /(.*)(*{ $count++ })[bc]/;
ok(!$matched, 'optimistic callback failure remains a failed match');
is($count, $length + 1,
    'optimistic callback preserves the trailing-class optimization');

$count = 0;
$matched = ($subject . 'b') =~ /(.*)(*{ $count++ })[bc]/;
ok($matched, 'optimized callback path still finds a reachable match');
is($count, 2, 'reachable greedy match retains Perl callback retry count');
is($&, $subject . 'b', 'optimized callback preserves the complete match');

$count = 0;
$matched = 'ab' =~ /a(*{ ++$count; 0 })b/;
ok($matched, 'an optimistic callback result does not become a condition');
is($count, 1, 'false-valued optimistic callback executes once');

$count = 0;
$matched = $subject =~ /\A(.*)(?{ $count++ })[bc]/;
ok(!$matched, 'anchored ordinary callback failure remains a failed match');
is($count, $length + 1,
    'anchoring limits ordinary callback retries to one start position');

$count = 0;
$matched = $subject =~ /\A(.*)(*{ $count++ })[bc]/;
is($count, $length + 1,
    'anchored optimistic callback retains complete backtracking semantics');

$count = 0;
$matched = $subject =~ /(.*)(?(*{ ++$count; 1 })[bc]|[de])/;
ok(!$matched, 'true optimistic condition retains failed-match semantics');
is($count, $length + 1,
    'true optimistic condition preserves trailing-class optimization');

$count = 0;
$matched = $subject =~ /(.*)(?(*{ ++$count; 0 })[bc]|[de])/;
ok(!$matched, 'false optimistic condition selects the failing else branch');
is($count, $length + 1,
    'false optimistic condition preserves trailing-class optimization');

$count = 0;
$matched = "aaaa\naaaa" =~ /(.*)(*{ ++$count })[bc]/;
ok(!$matched, 'optimistic callback failure checks each newline-delimited region');
is($count, 10,
    'leading dot-star optimization retains Perl line-start retry behavior');

$count = 0;
$matched = "aaaa\n" =~ /(.*)(*{ ++$count })[bc]/;
ok(!$matched, 'optimistic callback checks an empty line after final newline');
is($count, 6,
    'final-newline retry count includes the empty trailing line');
