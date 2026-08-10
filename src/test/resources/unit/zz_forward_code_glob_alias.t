#!/usr/bin/env perl

use strict;
use warnings;
use Test::More tests => 4;

{
    package Local::ForwardCodeAlias;

    my @methods = qw(author);
    *authors = \&author;

    for my $method (@methods) {
        no strict 'refs';
        *{$method} = sub { 'upstream author' };
    }
}

my $direct = eval { Local::ForwardCodeAlias::authors() };
is($@, '', 'calling a glob alias to a dynamically filled forward CV succeeds');
is($direct, 'upstream author', 'the direct alias uses the installed CV body');

my $method = eval { Local::ForwardCodeAlias->can('authors')->() };
is($@, '', 'a method lookup through the forward CV alias is callable');
is($method, 'upstream author', 'method lookup uses the installed CV body');
