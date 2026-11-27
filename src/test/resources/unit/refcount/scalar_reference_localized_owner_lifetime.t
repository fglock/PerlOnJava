use strict;
use warnings;
use B qw(svref_2object);
use Scalar::Util qw(weaken);
use Test::More tests => 6;

{
    package Local::LocalizedScalarReferenceOwner;
    our $destroyed = 0;

    sub new {
        my ($class) = @_;
        return bless {}, $class;
    }

    sub DESTROY {
        ++$destroyed;
    }
}

our $localized_reference;
my $weak;
sub ref_count { svref_2object($_[0])->REFCNT }

sub return_local_scalar_reference {
    my $object = Local::LocalizedScalarReferenceOwner->new;
    $weak = $object;
    weaken($weak);
    return \$object;
}

{
    local $localized_reference = return_local_scalar_reference();
    is($Local::LocalizedScalarReferenceOwner::destroyed, 0,
        'localized scalar reference keeps returned lexical contents alive');
    ok(defined $weak, 'localized scalar reference keeps the weak observer live');
    is(ref_count($$localized_reference), 1,
        'localization preserves Perl refcount for scalar-reference contents');
}

is($Local::LocalizedScalarReferenceOwner::destroyed, 1,
    'restoring the package scalar releases the final owner exactly once');
ok(!defined $weak, 'localization unwind clears the weak observer');
is($Local::LocalizedScalarReferenceOwner::destroyed, 1,
    'later cleanup does not repeat destruction');

done_testing;
