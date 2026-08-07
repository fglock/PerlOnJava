use strict;
use warnings;
use Test::More tests => 3;

my %groups = (
    first  => [qw(a b)],
    second => [qw(c d e)],
);

my $total = 2;
$total += @$_ for values %groups;
is $total, 7, 'postfix foreach preserves a lexical compound-assignment target';

my %targets = (
    pass => { list => [qw(one two three)] },
    fail => { list => [qw(four five)] },
);
my $runs = 1;
for my $state (qw(pass fail)) {
    my $size = @{ $targets{$state}{list} };
    $runs += $size * 2;
}
is $runs, 11, 'nested hash and array access keeps the accumulator scalar';

my $count = 0;
$count += keys %$_ for \%targets, \%groups;
is $count, 4, 'postfix foreach accepts scalarized hash operators on the RHS';
