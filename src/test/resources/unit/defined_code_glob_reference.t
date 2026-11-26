use strict;
use warnings;
use Test::More;
use Symbol qw(qualify_to_ref);

sub glob_reference_target { 'ok' }

my $glob = qualify_to_ref('glob_reference_target');
ok(defined(&$glob), 'defined(&globref) reads the referenced glob CODE slot');

my $missing = qualify_to_ref('missing_glob_reference_target');
ok(!defined(&$missing), 'undefined CODE slot remains undefined through a glob reference');

done_testing();
