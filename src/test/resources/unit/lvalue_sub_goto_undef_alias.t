use strict;
use warnings;
use Test::More;

my $slot;

sub lvalue_target :lvalue {
    $slot;
}

lvalue_target() = 'direct';
is($slot, 'direct', 'lvalue sub returns a writable undef slot');

undef $slot;
sub lvalue_goto_wrapper :lvalue {
    goto &lvalue_target;
}

lvalue_goto_wrapper() = 'assigned';
is($slot, 'assigned', 'goto lvalue wrapper forwards a writable undef slot');

sub empty_lvalue_return :lvalue {}
my $error;
eval { empty_lvalue_return() = 'replacement'; 1 } or $error = $@;
like($error, qr/^Can't return undef from lvalue subroutine/,
    'empty lvalue return remains invalid');

done_testing;
