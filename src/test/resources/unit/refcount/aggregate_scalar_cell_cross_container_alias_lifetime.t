use strict;
use warnings;
use B qw(svref_2object);
use Scalar::Util qw(weaken);
use feature 'refaliasing';
no warnings 'experimental::refaliasing';
use Test::More tests => 22;

{
    package CrossAggregateAliasLifetime;
    our $destroyed = 0;
    sub DESTROY { ++$destroyed }
}

sub refcount { svref_2object($_[0])->REFCNT }

my @source;
my @target;
my $object = bless {}, 'CrossAggregateAliasLifetime';
my $weak = $object;
weaken($weak);
$source[0] = $object;
\$target[0] = \$source[0];

is(refcount($object), 2,
    'one aliased scalar cell contributes one aggregate referent owner');
undef $object;
is(refcount($weak), 1, 'the aliased cell remains the only strong owner');

my $popped = pop @source;
ok(defined $weak, 'removing one aggregate alias keeps the shared cell alive');
undef $popped;
ok(defined $weak,
    'dropping the pop result leaves the other aggregate alias alive');

undef $target[0];
ok(!defined $weak, 'clearing the final alias releases the referent');
is($CrossAggregateAliasLifetime::destroyed, 1,
    'DESTROY runs exactly once after the last owning slot is cleared');

my %hash_source;
my @array_target;
my $hash_object = bless {}, 'CrossAggregateAliasLifetime';
my $hash_weak = $hash_object;
weaken($hash_weak);
$hash_source{slot} = $hash_object;
\$array_target[0] = \$hash_source{slot};

is(refcount($hash_object), 2,
    'a hash cell aliased into an array contributes one referent owner');
undef $hash_object;
is(refcount($hash_weak), 1,
    'the shared hash and array cell remains the only strong owner');

my $deleted = delete $hash_source{slot};
ok(defined $hash_weak, 'deleting one aggregate alias keeps the shared cell alive');
undef $deleted;
ok(defined $hash_weak,
    'dropping the delete result leaves the other aggregate alias alive');

undef $array_target[0];
ok(!defined $hash_weak, 'clearing the final hash/array alias releases the referent');
is($CrossAggregateAliasLifetime::destroyed, 2,
    'the cross-container referent is destroyed exactly once');

my @shift_source;
my @shift_target;
my $shift_object = bless {}, 'CrossAggregateAliasLifetime';
my $shift_weak = $shift_object;
weaken($shift_weak);
$shift_source[0] = $shift_object;
\$shift_target[0] = \$shift_source[0];

is(refcount($shift_object), 2,
    'one scalar cell shared with a second array contributes one referent owner');
undef $shift_object;
is(refcount($shift_weak), 1, 'the shared array cell remains the only strong owner');

my $shifted = shift @shift_source;
ok(defined $shift_weak, 'shifting one aggregate alias keeps the shared cell alive');
undef $shifted;
ok(defined $shift_weak,
    'dropping the shift result leaves the other aggregate alias alive');

undef $shift_target[0];
ok(!defined $shift_weak, 'clearing the final shifted alias releases the referent');
is($CrossAggregateAliasLifetime::destroyed, 3,
    'the shifted cross-container referent is destroyed exactly once');

my @clear_source;
my @clear_target;
my $clear_array_object = bless {}, 'CrossAggregateAliasLifetime';
my $clear_array_weak = $clear_array_object;
weaken($clear_array_weak);
$clear_source[0] = $clear_array_object;
\$clear_target[0] = \$clear_source[0];
undef $clear_array_object;
@clear_source = ();
ok(defined $clear_array_weak,
    'clearing an array preserves another aggregate alias');
undef $clear_target[0];
ok(!defined $clear_array_weak,
    'clearing the final array alias releases the referent');

my %clear_hash_source;
my @clear_hash_target;
my $clear_hash_object = bless {}, 'CrossAggregateAliasLifetime';
my $clear_hash_weak = $clear_hash_object;
weaken($clear_hash_weak);
$clear_hash_source{slot} = $clear_hash_object;
\$clear_hash_target[0] = \$clear_hash_source{slot};
undef $clear_hash_object;
%clear_hash_source = ();
ok(defined $clear_hash_weak,
    'clearing a hash preserves another aggregate alias');
undef $clear_hash_target[0];
ok(!defined $clear_hash_weak,
    'clearing the final hash alias releases the referent');

done_testing;
