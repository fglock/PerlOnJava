use strict;
use warnings;
use Test::More tests => 5;

{
    package StashLookupProbe;
    sub declared;
}

StashLookupProbe::compiler_placeholder() if 0;

ok(exists $StashLookupProbe::{declared}, 'forward declaration is visible in stash');
ok(!exists $StashLookupProbe::{compiler_placeholder},
   'undefined compiler lookup placeholder is not visible in stash');

sub StashLookupProbe::compiler_placeholder_extra { 1 }
ok(!exists $StashLookupProbe::{compiler_placeholder},
   'a longer defined symbol does not make a prefix glob visible');

my $dynamic_name = 'dynamic_missing';
ok(!exists $StashLookupProbe::{$dynamic_name},
   'dynamic missing stash key is not autovivified by exists');

sub StashLookupSource::imported_extra { 1 }
*StashLookupProbe::imported_extra = \&StashLookupSource::imported_extra;
ok(!exists $StashLookupProbe::{imported},
   'a longer imported glob does not make a prefix glob visible');
