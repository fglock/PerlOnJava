#!/usr/bin/perl
use strict;
use warnings;
use Test::More tests => 3;

sub prototyped_call_site($$;$$) {
    return (caller(0))[2];
}

my $expected_proto = __LINE__ + 1;
my $got_proto = prototyped_call_site("a", "b", "c", {
    k => 1,
});
is($got_proto, $expected_proto,
   'caller line for multiline non-& prototyped call reports expression start');

sub empty_prototype_call_site() {
    return (caller(0))[2];
}

my $expected_empty = __LINE__ + 1;
my $got_empty = empty_prototype_call_site(
);
is($got_empty, $expected_empty,
   'caller line for multiline empty-prototype call reports expression start');

sub unprototyped_call_site {
    return (caller(0))[2];
}

my $expected_unprototyped = __LINE__ + 3;
my $got_unprototyped = unprototyped_call_site(
    sub { 1 }
);
is($got_unprototyped, $expected_unprototyped,
   'caller line for multiline unprototyped named call remains closing line');
