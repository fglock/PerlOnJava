use strict;
use warnings;
use Test::More;

our @caller_info;
sub deleted_named_sub { @caller_info = caller(0) }

my $saved_sub = delete $::{deleted_named_sub};
$saved_sub->();
is($caller_info[3], 'main::deleted_named_sub',
   'a deleted named CV retains its name without a materialized glob reference');

done_testing;
