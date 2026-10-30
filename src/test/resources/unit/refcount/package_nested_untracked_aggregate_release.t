use strict;
use warnings;
use Test::More tests => 6;
use Scalar::Util qw(weaken isweak);

our @PACKAGE_NESTED_OWNER_ROOT;
our %PACKAGE_NESTED_OWNER_ROOT_HASH;

my $target = [];
my $weak = $target;
$PACKAGE_NESTED_OWNER_ROOT[0] = $target;
weaken($weak);
undef $target;

ok(isweak($weak), 'the observer is weak');
ok(defined($weak), 'the package array keeps the nested aggregate alive');

@PACKAGE_NESTED_OWNER_ROOT = ();
ok(!defined($weak), 'clearing the package array releases the final strong slot');

my $hash_target = [];
my $hash_weak = $hash_target;
$PACKAGE_NESTED_OWNER_ROOT_HASH{child} = $hash_target;
weaken($hash_weak);
undef $hash_target;

ok(isweak($hash_weak), 'the hash observer is weak');
ok(defined($hash_weak), 'the package hash keeps the nested aggregate alive');

delete $PACKAGE_NESTED_OWNER_ROOT_HASH{child};
ok(!defined($hash_weak), 'deleting the package hash entry releases the final strong slot');
