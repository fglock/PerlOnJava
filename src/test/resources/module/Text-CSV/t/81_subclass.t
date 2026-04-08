#!/usr/bin/perl

package Text::CSV::Subclass;

BEGIN {
    $ENV{PERL_TEXT_CSV} = $ENV{TEST_PERL_TEXT_CSV} || 0;
}

BEGIN { require Text::CSV; }	# needed for perl5.005

use strict;
$^W = 1;

use base "Text::CSV";

use Test::More tests => 6;

ok (1, "Subclassed");

my $csvs = Text::CSV::Subclass->new ();
is ($csvs->error_diag (), "", "Last failure for new () - OK");

my $sc_csv;
eval { $sc_csv = Text::CSV::Subclass->new ({ ecs_char => ":" }); };
is ($sc_csv, undef, "Unsupported option");
is ($@, "", "error");

is (Text::CSV::Subclass->error_diag (),
    "INI - Unknown attribute 'ecs_char'", "Last failure for new () - FAIL");

is (Text::CSV::Subclass->new ({ fail_me => "now" }), undef, "bad new ()");

1;
