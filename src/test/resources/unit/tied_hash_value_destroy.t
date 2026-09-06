use strict;
use warnings;
use Test::More;

my @events;
{
    package Local::TiedValue;
    sub TIESCALAR { bless {}, shift }
    sub FETCH { undef }
    sub STORE { }
    sub DESTROY { push @events, 'DESTROY' }
}

my %values;
tie $values{key}, 'Local::TiedValue';
delete $values{key};

is_deeply \@events, ['DESTROY'], 'deleting a tied hash value destroys its tie object';
done_testing;
