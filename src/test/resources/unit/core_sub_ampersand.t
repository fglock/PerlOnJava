use strict;
use warnings;
use Test::More;

sub current_sub_via_core_reference {
    no strict 'refs';
    &{"CORE::__SUB__"};
}

is(current_sub_via_core_reference(), \&current_sub_via_core_reference,
    'dynamic CORE::__SUB__ reference returns the invoking subroutine');

done_testing;
