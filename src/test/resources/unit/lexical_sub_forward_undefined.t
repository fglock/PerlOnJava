use strict;
use warnings;
use feature qw(lexical_subs state);
no warnings 'experimental::lexical_subs';
use Test::More;
use Scalar::Util qw(refaddr);

{
    my sub my_forward;
    eval { my_forward };
    like $@, qr/^Undefined subroutine &my_forward called at /,
        'bodyless my sub fails as an undefined named sub';
    eval { &my_forward };
    like $@, qr/^Undefined subroutine &my_forward called at /,
        'ampersand call to bodyless my sub has the same diagnostic';
}

{
    state sub state_forward;
    eval { state_forward };
    like $@, qr/^Undefined subroutine &state_forward called at /,
        'bodyless state sub fails as an undefined named sub';
    eval { &state_forward };
    like $@, qr/^Undefined subroutine &state_forward called at /,
        'ampersand call to bodyless state sub has the same diagnostic';
}

{
    package LexicalSubForwardShadow;
    sub state_shadowed { 43 }
    state sub state_shadowed;
    eval { state_shadowed() };
    Test::More::like $@, qr/^Undefined subroutine &state_shadowed called at /,
        'a bodyless state sub hides an already-defined package sub';
}

{
    package LexicalSubForwardMyShadow;
    sub my_shadowed { 43 }
    my sub my_shadowed;
    eval { my_shadowed };
    Test::More::like $@, qr/^Undefined subroutine &my_shadowed called at /,
        'a bodyless my sub hides an already-defined package sub in a bare call';
    eval { &my_shadowed };
    Test::More::like $@, qr/^Undefined subroutine &my_shadowed called at /,
        'a bodyless my sub hides an already-defined package sub in an ampersand call';
}

package main;

{
    state sub state_eval_forward;
    eval 'sub state_eval_forward { 17 }';
    is state_eval_forward(), 17,
        'a later eval definition fills a state-sub forward declaration';
}

{
    state sub state_eval_bare_forward;
    eval 'sub state_eval_bare_forward { 19 }';
    is state_eval_bare_forward, 19,
        'a later eval definition also fills a bare state-sub call';
}

{
    my sub my_eval_forward;
    eval 'sub my_eval_forward { 23 }';
    is my_eval_forward(), 23,
        'a later eval definition fills a my-sub forward declaration';
}

{
    state sub state_glob_forward;
    *lexical_sub_forward_glob = \&state_glob_forward;
    local *lexical_sub_forward_glob_target = *lexical_sub_forward_glob;
    eval 'sub lexical_sub_forward_glob_target { 42 }';
    is state_glob_forward(), 42,
        'an eval definition through a typeglob alias fills a state-sub forward declaration';
}

sub make_state_sub_closure {
    my ($value) = @_;
    return sub {
        state sub state_sub_closure { $value }
        state_sub_closure();
    };
}

is make_state_sub_closure(31)->(), 31,
    'a state sub captures the first value of its closure';
is make_state_sub_closure(37)->(), 37,
    'a state sub is independent in a separately cloned closure';

sub make_forwarded_state_sub_closure {
    return sub {
        state sub s1;
        state sub s2 { \&s1 };
        sub s1 { \&s2 }
        return \&s1;
    };
}

my $state_outer = make_forwarded_state_sub_closure();
my $state_s1 = $state_outer->();
my $state_s2 = $state_s1->();
is refaddr($state_s2->()), refaddr($state_s1),
    'a forwarded state sub closes over its sibling state-sub cell';
is refaddr($state_outer->()), refaddr($state_outer->()),
    'a forwarded state sub is retained by one anonymous-sub clone';
isnt refaddr(make_forwarded_state_sub_closure()->()), refaddr($state_outer->()),
    'a forwarded state sub is independent in a new anonymous-sub clone';

sub make_forwarded_my_sub_closure {
    return sub {
        my sub s1;
        my sub s2 { \&s1 };
        sub s1 { \&s2 }
        return \&s1;
    };
}

my $my_outer = make_forwarded_my_sub_closure();
my $my_s1 = $my_outer->();
my $my_s2 = $my_s1->();
is refaddr($my_s2->()), refaddr($my_s1),
    'a forwarded my sub closes over its sibling my-sub cell';
isnt refaddr($my_outer->()), refaddr($my_outer->()),
    'a forwarded my sub is recreated at each invocation';
isnt refaddr(make_forwarded_my_sub_closure()->()), refaddr($my_outer->()),
    'a forwarded my sub is independent in a new anonymous-sub clone';

done_testing;
