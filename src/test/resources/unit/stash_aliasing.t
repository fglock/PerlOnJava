#!/usr/bin/perl
use strict;
use warnings;
use Test::More;
no strict 'refs';

# Stash aliasing: *Dst:: = *Src::
# See dev/prompts/stash-aliasing-plan.md

subtest 'hash identity' => sub {
    package StashAliasSrc1;
    sub existing_sub { 42 }
    package main;

    *StashAliasDst1:: = *StashAliasSrc1::;
    ok( \%StashAliasDst1:: == \%StashAliasSrc1::,
        '\%Dst:: == \%Src::' );
    is( [keys %StashAliasDst1::]->[0], 'existing_sub',
        'keys %Dst:: shows existing Src entry' );
};

subtest 'sub installed via aliased package is visible under source' => sub {
    package StashAliasSrc2;
    package main;
    *StashAliasDst2:: = *StashAliasSrc2::;

    eval q{ sub StashAliasDst2::runtime_sub { "hello" } };
    die $@ if $@;

    ok( defined &StashAliasSrc2::runtime_sub, 'defined &Src::runtime_sub' );
    is( StashAliasSrc2::runtime_sub(), 'hello', 'call through Src:: works' );
};

subtest 'caller() reports the resolved package name' => sub {
    package StashAliasSrc3;
    package main;
    *StashAliasDst3:: = *StashAliasSrc3::;

    eval q{ sub StashAliasDst3::caller_sub { (caller(0))[3] } };
    die $@ if $@;

    is( StashAliasSrc3::caller_sub(), 'StashAliasSrc3::caller_sub',
        'caller(0)[3] reports the alias target, not Dst::Dst::name' );
};

subtest 'symbolic refs route through the alias' => sub {
    package StashAliasSrc4;
    our $x = 123;
    package main;
    *StashAliasDst4:: = *StashAliasSrc4::;

    is( ${"StashAliasDst4::x"}, 123,
        'symbolic $Dst::x sees $Src::x' );

    *{"StashAliasDst4::runtime_sym"} = sub { "via-sym" };
    ok( defined &StashAliasSrc4::runtime_sym,
        'runtime-installed sub via symbolic Dst:: ref is visible as Src::' );
};

subtest 'chain alias' => sub {
    package StashAliasChainSrc;
    sub chain_fn { "chain" }
    package main;

    *StashAliasChainMid:: = *StashAliasChainSrc::;
    *StashAliasChainDst:: = *StashAliasChainMid::;

    ok( \%StashAliasChainDst:: == \%StashAliasChainSrc::,
        'transitive alias: Dst == Src' );

    eval q{ sub StashAliasChainDst::new_fn { "new" } };
    die $@ if $@;

    ok( defined &StashAliasChainSrc::new_fn,
        'chained alias resolves through to terminal package' );
};

done_testing;
