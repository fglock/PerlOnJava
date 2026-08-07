#!/usr/bin/env perl

use strict;
use warnings;
no warnings 'once';
use B::Deparse;
use Storable qw(freeze thaw);
use Test::More tests => 2;

local $Storable::Deparse = 1;
my $frozen = freeze({ callback => sub { $_[0] ** 3 } });

my $source;
local $Storable::Eval = sub {
    $source = shift;
    return eval $source;
};
my $copy = thaw($frozen);
like($source, qr/sub\s*\{/,
    'Storable Eval receives deparsed subroutine source');
is($copy->{callback}->(3), 27,
    'the callback returned by Storable Eval remains callable');
