use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

my $bootstrap = [];
weaken($bootstrap);
my $weak;
{
    my $value = 42;
    $weak = \$value;
    weaken($weak);
    ok(defined $weak, 'lexical cell stays alive in scope');
    is($$weak, 42, 'lexical cell keeps its value');
    undef $value;
    ok(defined $weak, 'undef changes contents while cell remains alive');
    ok(!defined $$weak, 'weak cell sees undef contents');
}
ok(!defined $weak, 'final lexical owner releases its scalar cell');

my $strong;
{
    my $value = 'held';
    $strong = \$value;
    $weak = \$value;
    weaken($weak);
}
ok(defined $weak, 'escaped strong reference owns the cell after scope');
is($$strong, 'held', 'escaped cell retains contents');
undef $strong;
ok(!defined $weak, 'final escaped reference releases the cell');

my $callback;
{
    my $value = 7;
    $weak = \$value;
    weaken($weak);
    $callback = sub { $value };
}
ok(defined $weak, 'closure owns the captured scalar cell');
is($callback->(), 7, 'captured cell retains contents');
undef $callback;
ok(!defined $weak, 'final capture releases the scalar cell');

done_testing();
