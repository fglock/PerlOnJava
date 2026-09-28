use v5.36;
use feature 'switch';
no warnings 'experimental::smartmatch';
use Test::More;

my $total = 0;
for (1 .. 3) {
    when (1) { $total += 1 }
    when (2) { $total += 10 }
    when (3) { $total += 100 }
    default  { $total += 1000 }
}
is($total, 111,
    'implicit when exit advances a topicalizing foreach iteration');

my @seen;
for (1 .. 3) {
    when (2) { last }
    push @seen, $_;
}
is("@seen", '1', 'explicit last in a topicalizing foreach still exits the loop');

done_testing;
