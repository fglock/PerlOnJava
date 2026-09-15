use Test::More;

for my $operator (qw(all any)) {
    my $ok = eval "use feature 'keyword_$operator'; $operator length, qw(a b c)";
    ok(!$ok, "$operator requires a block");
    like($@, qr/syntax error/, "$operator reports a syntax error");
    like($@, qr/near \"$operator length\"/, "$operator anchors the error at the keyword");
}

done_testing;
