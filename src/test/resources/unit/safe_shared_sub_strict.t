use strict;
use warnings;
use Safe;
use Test::More;

{
    package SafeSharedSubProbe;
    sub value() { $_ }

    our $COMPARTMENT;
    BEGIN {
        $COMPARTMENT = Safe->new;
        $COMPARTMENT->permit(':base_core');
        $COMPARTMENT->share('value');
    }

    sub check {
        local $_ = 3;
        my $result = $COMPARTMENT->reval('value == 3');
        die $@ if $@;
        return $result;
    }
}

ok(SafeSharedSubProbe::check(),
    'Safe::share makes a shared sub visible to strict compartment eval');

done_testing();
