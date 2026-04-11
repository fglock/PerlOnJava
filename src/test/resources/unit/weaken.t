use Test::More;
use Scalar::Util qw(weaken isweak unweaken);

subtest 'isweak flag' => sub {
    my $ref = \my %hash;
    ok(!isweak($ref), "not weak initially");
    weaken($ref);
    ok(isweak($ref), "weak after weaken");
    unweaken($ref);
    ok(!isweak($ref), "not weak after unweaken");
};

subtest 'weak ref access' => sub {
    my $strong = { key => "value" };
    my $weak = $strong;
    weaken($weak);
    is($weak->{key}, "value", "can access through weak ref");
};

subtest 'copy of weak ref is strong' => sub {
    my $strong = { key => "value" };
    my $weak = $strong;
    weaken($weak);
    my $copy = $weak;
    ok(!isweak($copy), "copy is strong");
};

subtest 'weaken with DESTROY' => sub {
    my @log;
    { package WeakDestroy;
      sub new { bless {}, shift }
      sub DESTROY { push @log, "destroyed" } }
    my $strong = WeakDestroy->new;
    my $weak = $strong;
    weaken($weak);
    undef $strong;
    is_deeply(\@log, ["destroyed"], "DESTROY called when last strong ref gone");
    ok(!defined($weak), "weak ref is undef after DESTROY");
};

done_testing();
