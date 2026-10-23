use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More tests => 7;

{
    package Local::ScalarReferenceReturnOwner;
    our $destroyed = 0;

    sub new {
        my ($class) = @_;
        return bless {}, $class;
    }

    sub DESTROY {
        ++$destroyed;
    }
}

my $weak;
sub return_scalar_reference {
    my $object = Local::ScalarReferenceReturnOwner->new;
    $weak = $object;
    weaken($weak);
    return \$object;
}

my $value_ref = return_scalar_reference();
is($Local::ScalarReferenceReturnOwner::destroyed, 0,
    'returning a scalar reference keeps its lexical contents alive');
ok(defined $weak, 'the returned scalar reference keeps the weak observer live');

my @array_owners = ($value_ref);
my %hash_owners = (value => $value_ref);
undef $value_ref;
is($Local::ScalarReferenceReturnOwner::destroyed, 0,
    'array and hash owners keep returned scalar-reference contents alive');

undef @array_owners;
is($Local::ScalarReferenceReturnOwner::destroyed, 0,
    'releasing the array owner preserves the hash owner');
undef %hash_owners;
is($Local::ScalarReferenceReturnOwner::destroyed, 1,
    'releasing the final hash owner destroys the contents exactly once');
ok(!defined $weak, 'final scalar-reference release clears the weak observer');
is($Local::ScalarReferenceReturnOwner::destroyed, 1,
    'repeated cleanup does not dispatch DESTROY again');

done_testing;
