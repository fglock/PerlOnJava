use strict;
use warnings;
use Test::More;

my $text = 'alpha:beta:42:gamma:delta:42:epsilon:zeta';
my @matches;
pos($text) = 0;
push @matches, $& while $text =~ /(?:42|gamma|epsilon)/g;
is_deeply(\@matches, [qw(42 gamma 42 epsilon)], 'global literal alternation finds each branch in order');
is(pos($text), undef, 'completed global match clears pos');

my $branch_order = 'ab';
$branch_order =~ /(?:a|ab)/;
is($&, 'a', 'earlier literal alternative wins at the same position');

done_testing;
