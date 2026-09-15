use Test::More;

for my $case (
    ['delete $x', 'delete argument is not a HASH or ARRAY element or slice'],
    ['delete sort 1', 'delete argument is not a HASH or ARRAY element or slice'],
    ['exists $x', 'exists argument is not a HASH or ARRAY element or a subroutine'],
    ['exists &foo()', 'exists argument is not a subroutine name'],
) {
    my ($code, $message) = @$case;
    my $ok = eval $code;
    ok(!$ok, "$code is rejected");
    like($@, qr/\Q$message\E/, "$code reports its invalid argument");
    like($@, qr/line 1/, "$code reports the source line");
}

done_testing;
