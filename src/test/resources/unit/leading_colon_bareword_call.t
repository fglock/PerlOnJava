#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

sub leading_colon_target {
    return join ":", @_;
}

is(::leading_colon_target("a", "b"), "a:b", "leading :: call works with explicit parens");
::is(::leading_colon_target("c", "d"), "c:d", "leading :: call works without parens when sub exists");

{
    no strict 'subs';
    my $suffix = "prefix" . ::leading_colon_missing_sub;
    is($suffix, "prefix::leading_colon_missing_sub",
        "leading :: remains a bareword string when sub is missing");
}

done_testing;
