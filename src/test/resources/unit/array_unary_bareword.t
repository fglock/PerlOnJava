use Test::More;

for my $operator (qw(pop shift)) {
    my $ok = eval "$operator FRED; 1";
    ok(!$ok, "$operator rejects a bareword argument");
    like($@, qr/Type of arg 1 to \Q$operator\E must be array \(not constant item\)/,
        "$operator reports the array-argument diagnostic");
}

done_testing;
