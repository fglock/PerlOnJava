use strict;
use warnings;
use FindBin;
use Test::More tests => 2;

my $output = '';
open my $child_output, '-|', $^X, '-w',
    "$FindBin::Bin/warnings_prototype_mismatch_local_warn_flag_child.pl"
    or die "could not run child Perl process: $!";
$output .= $_ while <$child_output>;
close $child_output;

is($?, 0, 'child eval completed successfully');
is($output, "1,1\n",
    'prototype mismatch and constant redefinition warnings survive local $^W = 0');
