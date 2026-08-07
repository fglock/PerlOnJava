use strict;
use warnings;
use Test::More;
use Scalar::Util qw(isweak weaken);

my $holder = {};
push @{$holder->{array_slots}}, 'array value';
my $array_slot = \$holder->{array_slots}[-1];
weaken($array_slot);

ok(isweak($array_slot), 'reference to an array element becomes weak');
ok(defined($array_slot), 'weak array-element scalar reference remains defined');
is($$array_slot, 'array value', 'weak reference aliases the live array slot');
undef $$array_slot;
ok(!defined($holder->{array_slots}[0]),
    'assignment through weak reference updates autovivified array slot');

$holder->{hash_slots}{key} = 'hash value';
my $hash_slot = \$holder->{hash_slots}{key};
weaken($hash_slot);

ok(isweak($hash_slot), 'reference to a hash element becomes weak');
ok(defined($hash_slot), 'weak hash-element scalar reference remains defined');
is($$hash_slot, 'hash value', 'weak reference aliases the live hash slot');
undef $$hash_slot;
ok(!defined($holder->{hash_slots}{key}),
    'assignment through weak reference updates autovivified hash slot');

done_testing;
