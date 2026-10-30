use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More tests => 6;

our $destroyed = 0;
{
    package Local::RepeatedContainerEdge;
    sub DESTROY { ++$main::destroyed }
}

my $value = bless {}, 'Local::RepeatedContainerEdge';
my $weak = $value;
weaken($weak);
my $nested = [{ left => $value, right => $value }];
$value = undef;
ok(defined($weak), 'two nested element slots keep their shared referent alive');

$nested = undef;
ok(!defined($weak), 'dropping the outer owner releases both nested element slots');
is($destroyed, 1, 'the shared referent is destroyed exactly once');

my $cycle = bless {}, 'Local::RepeatedContainerEdge';
$cycle->{self} = $cycle;
my $weak_cycle = $cycle;
weaken($weak_cycle);
$cycle = undef;
ok(defined($weak_cycle), 'a strong cycle survives the final external owner');

delete $weak_cycle->{self};
ok(!defined($weak_cycle), 'breaking the cycle releases its final strong element');
is($destroyed, 2, 'cycle cleanup runs one additional destructor');
