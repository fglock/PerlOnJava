use Test::More;

for my $case (
    ['substr(%h, 0) = 3', 'substr'],
    ['(substr %h, 0) = 3', 'substr'],
    ['vec(%h, 1, 1) = 3', 'vec'],
    ['(vec %h, 1, 1) = 3', 'vec'],
) {
    my $ok = eval $case->[0];
    ok(!$ok, "$case->[0] is rejected");
    like($@, qr/Can't modify hash dereference in \Q$case->[1]\E/,
        'reports the aggregate lvalue diagnostic');
}

done_testing;
