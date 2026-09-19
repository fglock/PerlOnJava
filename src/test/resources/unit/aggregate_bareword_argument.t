use Test::More;

for my $operator (qw(keys values each)) {
    my $ok = eval "$operator FRED";
    ok(!$ok, "$operator rejects a bareword argument");
    like($@, qr/Type of arg 1 to $operator must be hash or array \(not constant item\)/,
        "$operator reports the argument type");
}

done_testing;
