#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

{
    no strict 'refs';
    q{$} =~ /(.)/;
    is($$1, $$, '$$1 parses as ${$1}');

    $main::doof = 'test';
    $main::test = 'Got here';
    $::{+$$} = *doof;
    is($$$$1, $main::test, '$$$$1 parses as ${${${$1}}}');
}

done_testing;
