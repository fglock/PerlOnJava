my $bless_override;
BEGIN {
    $bless_override = sub {
        return CORE::bless($_[0], @_ > 1 ? $_[1] : caller());
    };
    *CORE::GLOBAL::bless = sub { goto $bless_override };
}

require Test::More;
Test::More->import(tests => 1);

Test::More::pass('Test2 contexts survive a dynamic bless tail call during module load');
