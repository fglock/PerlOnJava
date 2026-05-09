#!/usr/bin/env perl

use strict;
use warnings;
use Test::More tests => 3;

eval { CanMissingSubroutine::missing() };
like($@, qr/Undefined subroutine/, 'missing static sub call fails');
ok(!CanMissingSubroutine->can('missing'), 'failed static sub call is not visible to can');

{
    no strict 'refs';
    my $ref = \&{'CanMissingSubroutine::created'};
    ok(CanMissingSubroutine->can('created'), 'symbolic CODE ref creation is visible to can');
}
