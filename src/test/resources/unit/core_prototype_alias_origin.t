use strict;
use warnings;
use Test::More tests => 1;

sub test_core_prototype_alias {
    my ($operator) = @_;

    no strict 'refs';
    *{"my$operator"} = \&{"CORE::$operator"};
    my $prototype = prototype("CORE::$operator");
    eval " &CORE::$operator(1,2) " if $prototype eq '_';
}

test_core_prototype_alias('prototype');

is(&myprototype(\&myprototype), prototype('CORE::prototype'),
    'a CORE prototype reference retains its prototype after a glob alias');
