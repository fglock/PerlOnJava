use strict;
use warnings;
use Test::More tests => 2;

our $saved_caller;
BEGIN {
    no warnings 'redefine';
    *CORE::GLOBAL::caller = sub (;$) {
        my ($height) = @_;
        return CORE::caller(($height || 0) + 1);
    };
}

sub dynamic_caller_proxy (;$) {
    my ($height) = @_;
    return $saved_caller->(($height || 0) + 1);
}

sub compile_under_caller_override {
    local $saved_caller = *CORE::GLOBAL::caller{CODE};
    local *CORE::GLOBAL::caller = \&dynamic_caller_proxy;
    eval q{sub caller_generated_under_override { caller }};
    die $@ if $@;
}

compile_under_caller_override();
is(caller_generated_under_override(), 'main',
    'eval caller uses the restored CORE::GLOBAL slot');
is(caller_generated_under_override(), 'main',
    'compiled caller remains callable on repeated invocation');
