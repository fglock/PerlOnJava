use strict;
use warnings;
use Test::More;

{
    package Local::StoreReturnsCount;
    our ($FETCHES, $STORES);

    sub TIESCALAR { bless { value => undef }, shift }
    sub FETCH { ++$FETCHES; return $_[0]{value} }
    sub STORE {
        ++$STORES;
        $_[0]{value} = $_[1];
        return $STORES;
    }
}

tie my $tied, 'Local::StoreReturnsCount';
my $assigned = $tied = 'assigned value';

is($assigned, 'assigned value', 'assignment expression returns the fetched tied value');
is($Local::StoreReturnsCount::FETCHES, 1, 'assignment expression fetches once after STORE');
is($Local::StoreReturnsCount::STORES, 1, 'assignment expression stores once');

done_testing();
