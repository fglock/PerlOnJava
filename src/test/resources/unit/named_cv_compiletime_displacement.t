use strict;
use warnings;
use Test::More tests => 4;

{
    no warnings 'redefine';
    sub compile_displaced { 'old' }
    my $compiled_call = sub { compile_displaced() };
    BEGIN { delete $main::{compile_displaced} }
    sub compile_displaced { 'new' }

    is $compiled_call->(), 'old',
        'a BEGIN-time replacement does not retarget an earlier compiled call';
    is 'main'->compile_displaced(), 'new',
        'the replacement remains visible as the current method';
}

{
    no warnings 'redefine';
    sub runtime_replaced { 'old' }
    my $saved = \&runtime_replaced;
    *runtime_replaced = sub { 'new' };

    is runtime_replaced(), 'new',
        'a runtime glob replacement retargets a direct named call';
    is $saved->(), 'old',
        'a saved code reference keeps the original CV';
}
