use strict;
use warnings;
use feature 'refaliasing';
no warnings 'experimental::refaliasing';
use Scalar::Util qw(weaken);
use Test::More;

{
    package RefaliasArrayCellOwnerTarget;
    our $destroyed = 0;
    sub DESTROY { ++$destroyed }
}

my @alias;
my $weak;
{
    my $object = bless {}, 'RefaliasArrayCellOwnerTarget';
    $weak = $object;
    weaken($weak);
    \(@alias) = \($object);
}

ok(defined $weak, 'aliased array slot retains the scalar cell after lexical scope exit');
is($RefaliasArrayCellOwnerTarget::destroyed, 0,
    'referent is not destroyed while the alias slot lives');
@alias = ();
ok(!defined $weak, 'clearing the final alias slot releases the referent');
is($RefaliasArrayCellOwnerTarget::destroyed, 1,
    'referent is destroyed exactly once');

done_testing;
