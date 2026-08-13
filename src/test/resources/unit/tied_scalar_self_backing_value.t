use strict;
use warnings;

use Test::More tests => 4;

{
    package Local::SelfBackingTie;

    sub TIESCALAR {
        my ($class, $owner) = @_;
        return bless \$owner, $class;
    }

    sub STORE {
        $_[1] ||= '';
        ${$_[0]}->{store_seen} = $_[1];
    }

    sub FETCH {
        return ${$_[0]}->{value};
    }
}

my $owner = {};
tie $owner->{value}, 'Local::SelfBackingTie', $owner;
$owner->{value} = 'current value';

is($owner->{store_seen}, 'current value', 'STORE sees the assigned value');
is($owner->{value}, 'current value', 'FETCH can read the tied scalar backing value recursively');
my $tie_object = tied($owner->{value});
is($$tie_object->{store_seen}, 'current value', 'tie object retains the owner identity');

$owner->{value} = 'replacement value';
is($owner->{value}, 'replacement value', 'repeated STORE can short-circuit ||= on a true readonly argument');
