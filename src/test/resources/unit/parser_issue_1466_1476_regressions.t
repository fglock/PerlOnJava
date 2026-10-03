#!/usr/bin/env perl
use strict;
use warnings;
use utf8;
use Test::More;

{
    package ParserIssue1466Input;
    sub FINDBIN { 'bin-dir' }

    package ParserIssue1466Result;
    sub load { 'loaded' }

    package main;
    sub class { bless {}, 'ParserIssue1466Result' }
}

my $class = bless {}, 'ParserIssue1466Input';
is(
    class($class->FINDBIN)->load,
    'loaded',
    'postfix method call works after a function call with method-call arguments',
);

my $transliterated = '（）＋−×÷';
$transliterated =~ tr[（）＋−×÷][\(\)\+\-\*\/];
is($transliterated, '()+-*/', 'escaped replacement literals remain literal in Unicode transliteration');

done_testing;
