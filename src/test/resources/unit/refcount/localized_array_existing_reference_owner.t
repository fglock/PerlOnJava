use strict;
use warnings;
use B qw(svref_2object);
use Scalar::Util qw(weaken);
use Test::More tests => 12;

{
    package LocalizedArrayExistingReferenceOwner;
    our %destroyed;

    sub new {
        my ($class, $id) = @_;
        return bless { id => $id }, $class;
    }

    sub DESTROY {
        ++$destroyed{$_[0]{id}};
    }
}

my @values = (LocalizedArrayExistingReferenceOwner->new('outer'));
my $weak_outer = $values[0];
weaken($weak_outer);
my $weak_local;
sub ref_count { svref_2object($_[0])->REFCNT }

{
    local $values[0];
    my $inner = LocalizedArrayExistingReferenceOwner->new('inner');
    $weak_local = $inner;
    weaken($weak_local);
    $values[0] = $inner;
    undef $inner;

    ok(defined $weak_outer, 'localization retains the saved outer reference');
    is(ref_count($weak_outer), 1, 'saved outer value has one strong owner');
    ok(defined $weak_local, 'localized slot owns its replacement reference');
    is(ref_count($weak_local), 1, 'localized replacement has one strong owner');
}

ok(defined $weak_outer, 'restoring the array slot keeps its original reference');
is(ref_count($weak_outer), 1, 'restored outer value has one strong owner');
ok(!defined $weak_local, 'restoring the array slot releases its local reference');
is($LocalizedArrayExistingReferenceOwner::destroyed{inner}, 1,
    'the localized reference is destroyed exactly once on restore');
is($LocalizedArrayExistingReferenceOwner::destroyed{outer} || 0, 0,
    'the saved reference is not destroyed during restore');

delete $values[0];
ok(!defined $weak_outer, 'deleting the restored slot releases its reference');
is($LocalizedArrayExistingReferenceOwner::destroyed{outer}, 1,
    'the restored reference is destroyed exactly once on deletion');
is($LocalizedArrayExistingReferenceOwner::destroyed{inner}, 1,
    'later cleanup does not repeat localized destruction');

done_testing;
