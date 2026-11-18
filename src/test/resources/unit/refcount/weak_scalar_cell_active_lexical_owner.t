use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

my $weak;
{
    my $value = 42;
    $weak = \$value;
    weaken($weak);

    ok(defined($weak), 'weak scalar reference is live inside its lexical scope');
    is($$weak, 42, 'weak scalar reference reads the live lexical value');
}

ok(!defined($weak), 'weak scalar reference clears when the lexical owner exits');

done_testing;
