use strict;
use warnings;
use Test::More;
use Scalar::Util qw(refaddr);

{
    package Local::PostfixReferenceOwner;
    use overload '++' => sub {
        ++$_[0]->{value};
        return $_[0];
    }, fallback => 1;

    our $destroy_count = 0;

    sub new {
        return bless { value => 0 }, shift;
    }

    sub DESTROY {
        ++$destroy_count;
    }
}

my $current = Local::PostfixReferenceOwner->new;
my $address = refaddr($current);
my $previous = $current++;

is(refaddr($current), $address,
    'postfix increment leaves the updated scalar owning the referent');
is(refaddr($previous), $address,
    'the postfix result keeps the same referent alive');
is($current->{value}, 1, 'the overloaded increment updates the referent');

undef $previous;
is($Local::PostfixReferenceOwner::destroy_count, 0,
    'dropping the postfix result does not destroy the live scalar value');
is($current->{value}, 1, 'the scalar value remains usable');

undef $current;
is($Local::PostfixReferenceOwner::destroy_count, 1,
    'the referent is destroyed after its final Perl owner leaves');

done_testing;
