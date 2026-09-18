use Test::More;

for my $case (
    ['my', 'my sub;', 1],
    ['our', 'our sub;', 1],
    ['state', "use 5.010;\nstate sub;", 2],
) {
    my ($declaration, $source, $line) = @$case;
    my $ok = eval $source;
    ok(!defined $ok, "$declaration sub without a name does not compile");
    like($@, qr/^Missing name in "\Q$declaration\E sub" at \(eval \d+\) line $line\./,
         "$declaration sub without a name reports its declaration kind");
}

done_testing;
