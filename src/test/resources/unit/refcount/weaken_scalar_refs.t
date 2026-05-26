use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

sub _poj_weaken_scalar_temp {
    return 1;
}

{
    my $dummy = [];
    weaken($dummy);

    my $ref = \(_poj_weaken_scalar_temp());
    weaken($ref);
    ok(!defined($ref), "weak ref to returned scalar temporary clears after prior weaken");
}

{
    my $dummy = [];
    weaken($dummy);

    my $weak;
    {
        my $x = 42;
        $weak = \$x;
        weaken($weak);
        ok(defined($weak), "weak ref to live lexical scalar remains defined");
        is($$weak, 42, "weak ref reads live lexical scalar");
    }
    ok(!defined($weak), "weak ref to lexical scalar clears after scope exit");
}

{
    my $dummy = [];
    weaken($dummy);

    my $strong_ref;
    my $weak;
    {
        my $x = "held";
        $strong_ref = \$x;
        $weak = \$x;
        weaken($weak);
    }
    ok(defined($weak), "weak scalar ref survives while another scalar ref is strong");
    is($$weak, "held", "escaped strong scalar ref preserves value");
    undef $strong_ref;
    ok(!defined($weak), "weak scalar ref clears after escaped strong ref is dropped");
}

sub _poj_readonly_scalar_ref {
    return \ "yay";
}

{
    my $ref = _poj_readonly_scalar_ref();
    weaken($ref);

    is($$ref, "yay", "weak ref to readonly pad constant remains alive while sub is installed");
    ok(Scalar::Util::isweak($ref), "readonly pad constant ref is weak");

    { no warnings "redefine"; *_poj_readonly_scalar_ref = sub {} }
    ok(!defined($ref), "weak ref to readonly pad constant clears when sub is redefined");
}

done_testing();
