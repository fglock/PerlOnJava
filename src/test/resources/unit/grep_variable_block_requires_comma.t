use strict;
use warnings;

my $predicate;
eval 'grep $predicate (1, 2, 3);';
print($@ =~ /^Missing comma after first argument to grep function/
    ? "1..1\nok 1 - grep reports missing comma after a scalar predicate\n"
    : "1..1\nnot ok 1 - grep reports missing comma after a scalar predicate\n");
