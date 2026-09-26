use strict;
use warnings;
use Test::More tests => 2;

sub fop () { 0 }
sub bas () { 0 }
{ local $SIG{__WARN__} = sub {}; eval 'fop bas'; }

is(ref $::{fop}, 'SCALAR', 'first constant remains a compact scalar stash entry');
is(ref $::{bas}, 'SCALAR', 'second constant remains a compact scalar stash entry');
