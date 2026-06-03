#!/usr/bin/perl
use strict;
use warnings;
use Test::More tests => 5;

sub direct_caller_line {
    return (caller(0))[2];
}

my $expected_direct_line = __LINE__ + 1;
my $direct_line = direct_caller_line(
    1,
    2
);
is($direct_line, $expected_direct_line, 'ordinary multiline direct call reports opening line');

{
    package CallerMultilineDataCallLine::Obj;
    sub new { bless {}, shift }
    sub method_caller_line {
        return (caller(0))[2];
    }
}

my $expected_method_line = __LINE__ + 1;
my $method_line = CallerMultilineDataCallLine::Obj->new->method_caller_line(
    1,
    2
);
is($method_line, $expected_method_line, 'ordinary multiline method call reports opening line');

sub literal_sub_arg_caller_line {
    return (caller(0))[2];
}

my $expected_literal_sub_line = __LINE__ + 3;
my $literal_sub_line = literal_sub_arg_caller_line(
    sub { 1 }
);
is($literal_sub_line, $expected_literal_sub_line, 'literal anon sub argument still reports block line');

my $expected_method_literal_sub_line = __LINE__ + 3;
my $method_literal_sub_line = CallerMultilineDataCallLine::Obj->new->method_caller_line(
    sub { 1 }
);
is($method_literal_sub_line, $expected_method_literal_sub_line, 'method literal anon sub argument still reports block line');

my $coderef = sub { return (caller(0))[2] };
my $expected_coderef_line = __LINE__ + 1;
my $coderef_line = $coderef->(
    "argument"
);
is($coderef_line, $expected_coderef_line, 'ordinary multiline coderef arrow call reports opening line');
