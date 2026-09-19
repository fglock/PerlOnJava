use Test::More;

my $ok = eval q{
    our @a;
    @a->{0};
    1;
};

ok(!$ok, 'an array used as a hash reference is rejected');
like($@, qr/Can't use an undefined value as a HASH reference/,
    'reports an undefined hash-reference value');

done_testing;
