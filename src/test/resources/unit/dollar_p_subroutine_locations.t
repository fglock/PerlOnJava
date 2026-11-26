use strict;
use warnings;
use Test::More tests => 3;

sub defined_before_subroutine_tracing { 1 }

BEGIN { $^P |= 0x10 }

package DollarPTrace;
sub defined_after_subroutine_tracing { 1 }

package main;
ok(!exists $DB::sub{'main::defined_before_subroutine_tracing'},
    'subroutines defined before $^P subroutine tracing are not added');
ok(exists $DB::sub{'DollarPTrace::defined_after_subroutine_tracing'},
    'subroutines defined after $^P subroutine tracing are added');
like($DB::sub{'DollarPTrace::defined_after_subroutine_tracing'}, qr/:\d+-\d+\z/,
    '%DB::sub records the defining source range');

done_testing;
