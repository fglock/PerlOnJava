#!/usr/bin/perl
#
# jcpan - CPAN client for PerlOnJava
#
# This is a thin wrapper around App::Cpan, matching the standard `cpan` script.
# Run `jcpan -h` for usage information.
#

use strict;
use warnings;

use App::Cpan;

my $rc = App::Cpan->run(@ARGV);

exit($rc || 0);
