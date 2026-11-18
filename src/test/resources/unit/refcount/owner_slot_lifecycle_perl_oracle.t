use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken isweak);

our %DESTROYED;
our (@PACKAGE_CODE, $CODE_WEAK);

{
    package Issue2043::OwnerSlotLifecycle;
    sub new { bless { name => $_[1] }, $_[0] }
    sub DESTROY { ++$main::DESTROYED{$_[0]{name}} }
}

sub make_owner_reader {
    my ($value) = @_;
    return sub { $value };
}

{
    my $left = Issue2043::OwnerSlotLifecycle->new('scalar');
    my $right = $left;
    my $weak = $left;
    weaken($weak);

    undef $left;
    ok(defined($weak), 'one remaining scalar owner keeps the referent alive');
    is($DESTROYED{scalar} || 0, 0, 'DESTROY waits for the final scalar owner');

    undef $right;
    ok(!defined($weak), 'the final scalar owner clears its weak observer');
    is($DESTROYED{scalar}, 1, 'the final scalar release calls DESTROY once');
}

{
    my $object = Issue2043::OwnerSlotLifecycle->new('array');
    my $array = [$object];
    my $weak = $object;
    weaken($weak);

    undef $object;
    ok(defined($weak), 'an array element remains a strong owner');
    is($DESTROYED{array} || 0, 0, 'DESTROY waits while the array slot is live');

    pop @$array;
    ok(!defined($weak), 'removing the final array element clears its weak observer');
    is($DESTROYED{array}, 1, 'array element release calls DESTROY once');
}

{
    my $object = Issue2043::OwnerSlotLifecycle->new('closure');
    my $weak = $object;
    weaken($weak);
    my $reader = make_owner_reader($object);

    undef $object;
    ok(defined($weak), 'a live closure capture keeps the referent alive');
    is(ref($reader->()), 'Issue2043::OwnerSlotLifecycle',
        'the closure can still read its captured referent');

    undef $reader;
    ok(!defined($weak), 'releasing the final closure capture clears its weak observer');
    is($DESTROYED{closure}, 1, 'closure release calls DESTROY once');
}

{
    $Issue2043::PACKAGE_OWNER = Issue2043::OwnerSlotLifecycle->new('package');
    my $weak = $Issue2043::PACKAGE_OWNER;
    weaken($weak);
    ok(isweak($weak), 'the package-owner observer is weak');
    ok(defined($weak), 'the package slot keeps the referent alive');
    is($DESTROYED{package} || 0, 0, 'DESTROY waits while the package slot is live');

    undef $Issue2043::PACKAGE_OWNER;
    ok(!defined($weak), 'removing the package owner clears its weak observer');
    is($DESTROYED{package}, 1, 'package-slot release calls DESTROY once');
}

{
    my $value = 0;
    my $code = sub { ++$value };
    $CODE_WEAK = $code;
    weaken($CODE_WEAK);
    @PACKAGE_CODE = ($code);
    undef $code;

    ok(defined($CODE_WEAK), 'a package array element keeps a capturing CODE value alive');
    is($PACKAGE_CODE[0]->(), 1, 'the captured CODE value remains callable through its array slot');

    @PACKAGE_CODE = ();
    ok(!defined($CODE_WEAK), 'releasing the final array CODE slot clears its weak observer');
}

{
    package Issue2043::OwnerSlotTie;
    sub TIEHASH { bless { handler => $_[1] }, $_[0] }
    sub FETCH { $_[0]->{handler}{$_[1]} }
    sub STORE { $_[0]->{handler}{$_[1]} = $_[2] }
}

{
    my $object = Issue2043::OwnerSlotLifecycle->new('tied');
    my $weak = $object;
    weaken($weak);
    my $handler = { nested => { target => $object } };
    tie my %tied, 'Issue2043::OwnerSlotTie', $handler;

    undef $object;
    undef $handler;
    ok(defined($weak), 'the tied handler and nested hash retain the referent');
    is($DESTROYED{tied} || 0, 0, 'DESTROY waits while the nested tied value is live');

    untie %tied;
    ok(!defined($weak), 'untie releases the final nested value owner');
    is($DESTROYED{tied}, 1, 'tied-handler release calls DESTROY once');
}

done_testing;
