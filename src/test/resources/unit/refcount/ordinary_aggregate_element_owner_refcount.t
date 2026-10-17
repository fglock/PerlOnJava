use strict;
use warnings;
use B qw(svref_2object);
use Test::More;

{
    package OrdinaryAggregateOwnerProbe;
    our $destroyed = 0;
    sub DESTROY { ++$destroyed }
}

sub rc { svref_2object($_[0])->REFCNT }

my $object = bless {}, 'OrdinaryAggregateOwnerProbe';
is(rc($object), 1, 'the lexical starts as the only strong owner');

my @array = ($object);
is(rc($object), 2, 'an array element adds one owner');
push @array, $object;
is(rc($object), 3, 'push adds one independent array-element owner');
shift @array;
is(rc($object), 2, 'shift releases only the removed array-element owner');

my %hash = (left => $object, right => $object);
is(rc($object), 4, 'two hash entries add two independent owners');
delete $hash{left};
is(rc($object), 3, 'delete releases only the removed hash-entry owner');
$hash{right} = bless {}, 'OrdinaryAggregateOwnerProbe';
is(rc($object), 2, 'replacing a hash value releases the displaced owner');

undef @array;
is(rc($object), 1, 'clearing the array releases its final element owner');
undef %hash;
is($OrdinaryAggregateOwnerProbe::destroyed, 1,
    'the replaced hash value is destroyed exactly once');
is(rc($object), 1, 'clearing aggregates does not release the lexical owner');
undef $object;
is($OrdinaryAggregateOwnerProbe::destroyed, 2,
    'the final lexical owner releases the remaining object exactly once');

done_testing;
