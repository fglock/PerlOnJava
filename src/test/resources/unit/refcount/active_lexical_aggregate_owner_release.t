use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

my ($weak_array, $weak_hash);

sub create_weak_aggregate_observers {
    my @array = (1, 2, 3);
    my %hash = (value => 1);

    $weak_array = \@array;
    $weak_hash = \%hash;
    weaken($weak_array);
    weaken($weak_hash);

    ok(defined($weak_array), 'array referent lives while its lexical scope is active');
    ok(defined($weak_hash), 'hash referent lives while its lexical scope is active');
}

create_weak_aggregate_observers();

ok(!defined($weak_array), 'normal scope exit clears the final weak array observer');
ok(!defined($weak_hash), 'normal scope exit clears the final weak hash observer');

done_testing();
