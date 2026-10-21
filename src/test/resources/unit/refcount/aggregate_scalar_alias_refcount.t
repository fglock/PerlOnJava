use strict;
use warnings;
use B qw(svref_2object);
use Scalar::Util qw(weaken);
use Test::More tests => 8;
use feature 'refaliasing';
no warnings 'experimental::refaliasing';

our $destroyed = 0;

sub refcount { svref_2object($_[0])->REFCNT }

my @array;
my $array_object = bless {}, 'AggregateScalarAliasRefcount::Tracked';
my $array_weak = $array_object;
weaken($array_weak);
push @array, $array_object;
\my $array_alias = \$array[0];

is(refcount($array_object), 2,
    'an array element stores one owner regardless of scalar aliases');
undef $array_object;
is(refcount($array_weak), 1,
    'the array element is the sole owner after lexical release');
$array_alias = undef;
ok(!defined $array_weak, 'clearing an array-element alias releases the referent');
is($destroyed, 1, 'the array-element alias triggers DESTROY exactly once');

my %hash;
my $hash_object = bless {}, 'AggregateScalarAliasRefcount::Tracked';
my $hash_weak = $hash_object;
weaken($hash_weak);
$hash{entry} = $hash_object;
\my $hash_alias = \$hash{entry};

is(refcount($hash_object), 2,
    'a hash entry stores one owner regardless of scalar aliases');
undef $hash_object;
is(refcount($hash_weak), 1,
    'the hash entry is the sole owner after lexical release');
$hash_alias = undef;
ok(!defined $hash_weak, 'clearing a hash-element alias releases the referent');
is($destroyed, 2, 'the hash-element alias triggers DESTROY exactly once');

package AggregateScalarAliasRefcount::Tracked;
sub DESTROY { ++$main::destroyed }
