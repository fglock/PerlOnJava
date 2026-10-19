use strict;
use warnings;
use Test::More tests => 8;
use Scalar::Util qw(refaddr);
use Storable qw(freeze thaw nfreeze);

my $shared = [17];
my $graph = { left => $shared, right => $shared };
$graph->{self} = $graph;

for my $serializer ([freeze => \&freeze], [nfreeze => \&nfreeze]) {
    my ($name, $encode) = @$serializer;
    my $value = $graph;
    my $thawed = thaw($encode->(\$value));
    is(ref($thawed), 'REF', "$name preserves MLDBM's scalar-reference envelope");

    my $copy = ref($thawed) eq 'REF' ? $$thawed : $thawed;
    is($copy->{left}[0], 17, "$name retains a stored reference value");
    is(refaddr($copy->{left}), refaddr($copy->{right}), "$name preserves shared references");
    is(refaddr($copy->{self}), refaddr($copy), "$name preserves circular references");
}
