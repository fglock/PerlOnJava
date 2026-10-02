#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 2;

sub optional_underscore ($;_) { return @_ }

{
    local $_ = 'default';
    my @args = optional_underscore('required');
    is_deeply(\@args, ['required'],
        'omitted optional underscore does not add $_ after a required argument');
}

my @explicit = optional_underscore('required', 'explicit');
is_deeply(\@explicit, ['required', 'explicit'],
    'optional underscore accepts an explicit second argument');
