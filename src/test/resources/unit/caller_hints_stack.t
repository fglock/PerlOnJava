use strict;
use warnings;
use Test::More;

sub outer {
    use integer;
    return middle();
}

sub middle {
    no integer;
    return inner();
}

sub inner {
    my @immediate = caller(1);
    my @outer = caller(2);
    return ($immediate[8], $outer[8]);
}

my ($middle_hints, $outer_hints) = outer();
isnt($middle_hints, $outer_hints, 'nested caller hint frames preserve their order');
ok(defined $middle_hints, 'caller supplies a defined $^H value');

done_testing;
