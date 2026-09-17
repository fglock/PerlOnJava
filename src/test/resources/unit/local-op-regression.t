use strict;
use warnings;
use Test::More;

{
    package LocalRegressionTieHash;
    sub TIEHASH { bless {}, shift }
    sub FETCH   { $_[0]->{$_[1]} }
    sub STORE   { $_[0]->{$_[1]} = $_[2] }
    sub EXISTS  { exists $_[0]->{$_[1]} }
    sub DELETE  { delete $_[0]->{$_[1]} }
}

{
    tie my %hash, 'LocalRegressionTieHash';
    $hash{value} = 3;
    {
        local($hash{value});
        delete $hash{value};
    }
    is $hash{value}, 3, 'bare tied hash-element local restores its value';
}

{
    our ($stores, $fetches) = (0, 0);
    {
        package LocalRegressionNoFetch;
        sub TIEHASH { bless {}, shift }
        sub FETCH   { ++$main::fetches; 42 }
        sub STORE   { ++$main::stores }
    }
    tie my %hash, 'LocalRegressionNoFetch';
    eval { for ($hash{value}) { local $_ = 2 } };
    is $stores, 0, 'foreach over tied hash element does not store';
    is $fetches, 0, 'foreach over tied hash element does not fetch';
}

done_testing;
