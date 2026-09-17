use strict;
use warnings;
use Test::More;

sub glob_snapshot_code_undef { 'retained CV' }

my $glob = *glob_snapshot_code_undef;
undef *glob_snapshot_code_undef;

no strict 'refs';
is(&$glob, 'retained CV',
   'a copied typeglob retains its CODE slot after undef *glob');

done_testing;
