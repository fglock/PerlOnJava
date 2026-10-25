use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

{
    package HashClearScalarRefOwnerProbe;
    our $DESTROYED = 0;
    sub new { bless {}, shift }
    sub DESTROY { ++$DESTROYED }
}

my %strong_values;
my $strong_object = HashClearScalarRefOwnerProbe->new;
my $strong_weak = $strong_object;
weaken($strong_weak);
$strong_values{item} = $strong_object;
my $strong_alias = \$strong_values{item};
undef $strong_object;
undef %strong_values;
is($HashClearScalarRefOwnerProbe::DESTROYED, 0,
   'strong scalar reference keeps the hash value alive after clear');
is(ref($strong_weak), 'HashClearScalarRefOwnerProbe',
   'weak observer remains live through the scalar reference');
undef $strong_alias;
is($HashClearScalarRefOwnerProbe::DESTROYED, 1,
   'destroy runs after the final scalar reference is released');
ok(!defined($strong_weak), 'weak observer clears after final scalar reference release');

my %weak_values;
my $weak_object = HashClearScalarRefOwnerProbe->new;
my $weak_observer = $weak_object;
weaken($weak_observer);
$weak_values{item} = $weak_object;
my $weak_alias = \$weak_values{item};
weaken($weak_alias);
undef $weak_object;
undef %weak_values;
is($HashClearScalarRefOwnerProbe::DESTROYED, 2,
   'weak scalar reference does not retain the hash value after clear');
ok(!defined($weak_observer), 'weak observer clears after hash clear');

my %deleted_values;
my $deleted_object = HashClearScalarRefOwnerProbe->new;
my $deleted_weak = $deleted_object;
weaken($deleted_weak);
$deleted_values{item} = $deleted_object;
my $deleted_alias = \$deleted_values{item};
undef $deleted_object;
my $deleted_value = delete $deleted_values{item};
undef $deleted_value;
is($HashClearScalarRefOwnerProbe::DESTROYED, 2,
   'delete preserves the detached scalar cell while a strong refalias remains');
ok(defined($deleted_weak), 'the deleted value remains alive through its refalias');
undef $deleted_alias;
is($HashClearScalarRefOwnerProbe::DESTROYED, 3,
   'releasing the final deleted-cell refalias runs DESTROY');
ok(!defined($deleted_weak), 'the deleted value weak observer clears after refalias release');

my @array_values;
my $array_object = HashClearScalarRefOwnerProbe->new;
my $array_weak = $array_object;
weaken($array_weak);
$array_values[0] = $array_object;
my $array_alias = \$array_values[0];
undef $array_object;
undef @array_values;
is($HashClearScalarRefOwnerProbe::DESTROYED, 3,
   'clearing an array preserves the detached scalar cell through a refalias');
ok(defined($array_weak), 'the array value remains alive through its refalias');
undef $array_alias;
is($HashClearScalarRefOwnerProbe::DESTROYED, 4,
   'releasing the final array-cell refalias runs DESTROY');
ok(!defined($array_weak), 'the array value weak observer clears after refalias release');

my @deleted_array_values;
my $deleted_array_object = HashClearScalarRefOwnerProbe->new;
my $deleted_array_weak = $deleted_array_object;
weaken($deleted_array_weak);
$deleted_array_values[0] = $deleted_array_object;
my $deleted_array_alias = \$deleted_array_values[0];
undef $deleted_array_object;
my $deleted_array_value = delete $deleted_array_values[0];
undef $deleted_array_value;
is($HashClearScalarRefOwnerProbe::DESTROYED, 4,
   'array delete preserves the detached scalar cell through a refalias');
ok(defined($deleted_array_weak), 'the deleted array value remains alive through its refalias');
undef $deleted_array_alias;
is($HashClearScalarRefOwnerProbe::DESTROYED, 5,
   'releasing the final deleted array-cell refalias runs DESTROY');
ok(!defined($deleted_array_weak), 'the deleted array value weak observer clears after refalias release');

done_testing;
