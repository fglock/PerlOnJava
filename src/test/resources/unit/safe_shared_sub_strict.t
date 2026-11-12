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

{
    package SafeUnsharedSubProbe;
    sub hidden() { 3 }

    our $COMPARTMENT;
    BEGIN {
        $COMPARTMENT = Safe->new;
        $COMPARTMENT->permit(':base_core');
    }

    sub check {
        return $COMPARTMENT->reval('hidden() == 3');
    }
}

my $unshared_result = SafeUnsharedSubProbe::check();
ok(!defined($unshared_result),
    'Safe compartment eval cannot call a sub that was not shared');
like($@, qr/Undefined subroutine .*hidden/,
    'unshared sub failure is reported by the compartment');

done_testing();
