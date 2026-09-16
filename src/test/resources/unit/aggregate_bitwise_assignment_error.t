use Test::More;

for my $case (
    ['@a &= 1', 'numeric bitwise and (&)'],
    ['@a |= 1', 'numeric bitwise or (|)'],
    ['@a ^= 1', 'numeric bitwise xor (^)'],
    ['@a &.= 1', 'string bitwise and (&.)'],
    ['@a |.= 1', 'string bitwise or (|.)'],
    ['@a ^.= 1', 'string bitwise xor (^.)'],
) {
    my $ok = eval "use feature 'bitwise'; $case->[0];";
    ok(!$ok, "$case->[0] is rejected");
    like($@, qr/Can't modify array dereference in \Q$case->[1]\E/,
        'reports the aggregate bitwise diagnostic');
}

done_testing;
