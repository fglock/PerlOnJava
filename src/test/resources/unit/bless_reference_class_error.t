use Test::More;

for my $source (
    q{bless {}, []},
    q{my $class = []; bless {}, $class},
    q{sub f {} bless [], bless []},
    q{sub TIESCALAR { bless [] } sub FETCH { [] } tie my $class, ''; bless {}, $class},
) {
    my $ok = eval $source;
    ok(!$ok, 'bless rejects a reference class name');
    like($@, qr/Attempt to bless into a reference/, 'reports Perl-compatible diagnostic');
}

done_testing;
