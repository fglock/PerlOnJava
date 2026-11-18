use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);
{
    package DeleteMiddleOwnerTracker;
    our $DESTROY_COUNT = 0;
    sub DESTROY { ++$DESTROY_COUNT }
}

my $array = [undef, bless({}, 'DeleteMiddleOwnerTracker'), undef];
my $weak = $array->[1];
weaken($weak);
my $deleted = delete $array->[1];
is($DeleteMiddleOwnerTracker::DESTROY_COUNT, 0,
    'delete result retains the removed value');
ok(defined $weak, 'weak observer remains while the returned value is live');
undef $deleted;
is($DeleteMiddleOwnerTracker::DESTROY_COUNT, 1,
    'removed value is destroyed after its returned owner is released');
ok(!defined $weak, 'weak observer clears after the final owner leaves');

my $void_array = [undef, bless({}, 'DeleteMiddleOwnerTracker'), undef];
my $void_weak = $void_array->[1];
weaken($void_weak);
delete $void_array->[1];
is($DeleteMiddleOwnerTracker::DESTROY_COUNT, 2,
    'void-context middle delete releases its removed value');
ok(!defined $void_weak,
    'void-context middle delete clears the final weak observer');

done_testing;
