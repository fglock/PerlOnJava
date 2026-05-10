#!/usr/bin/perl
use strict;
use warnings;
use Test::More tests => 3;

sub caller_line {
    return (caller(1))[2];
}

sub code_and_scalar (&$) {
    my ($code, $value) = @_;
    return caller_line();
}

my $expected_scalar_line = __LINE__ + 3;
my $scalar_line = code_and_scalar(
    sub { 1 },
    42
);
is($scalar_line, $expected_scalar_line, 'caller line for (&$) is the scalar argument');

sub code_and_list (&@) {
    my ($code, @values) = @_;
    return caller_line();
}

my $expected_list_line = __LINE__ + 3;
my $list_line = code_and_list(
    sub { 1 },
    { answer => 42 },
    'name'
);
is($list_line, $expected_list_line, 'caller line for (&@) is the first non-code argument');

sub code_only (&) {
    my ($code) = @_;
    return caller_line();
}

my $expected_code_only_line = __LINE__ + 3;
my $code_only_line = code_only(
    sub { 1 }
);
is($code_only_line, $expected_code_only_line, 'caller line for (&) remains the closing line');
