#!/usr/bin/perl

use strict;
$^W = 1;

use Test::More;

BEGIN {
    if ($] < 5.008001) {
        plan skip_all => "This test unit requires perl-5.8.1 or higher";
	}
    else {
	plan tests => 32;
	}

    $ENV{PERL_TEXT_CSV} = $ENV{TEST_PERL_TEXT_CSV} || 0;

    use_ok "Text::CSV", "csv";
    require "./t/util.pl";
    }

my $tfn = "_68test.csv"; END { unlink $tfn, "_$tfn"; }

my @dta = (
    [qw( foo  bar   zap		)],
    [qw( mars venus pluto	)],
    [qw( 1    2     3		)],
    );
my @dth = (
    { foo => "mars", bar => "venus", zap => "pluto" },
    { foo => 1,      bar => 2,       zap => 3       },
    );

{   open my $fh, ">", $tfn or die "$tfn: $!\n";
    local $" = ",";
    print $fh "@$_\n" for @dta;
    close $fh;
    }

is_deeply (csv (in => $tfn),                              \@dta, "csv ()");
is_deeply (csv (in => $tfn, bom => 1),                    \@dth, "csv (bom)");
is_deeply (csv (in => $tfn,           headers => "auto"), \@dth, "csv (headers)");
is_deeply (csv (in => $tfn, bom => 1, headers => "auto"), \@dth, "csv (bom, headers)");

foreach my $arg ("", "bom", "auto", "bom, auto") {
    open my $fh, "<", $tfn or die "$tfn: $!\n";
    my %attr;
    $arg =~ m/bom/	and $attr{bom}     = 1;
    $arg =~ m/auto/	and $attr{headers} = "auto";
    ok (my $csv = Text::CSV->new (), "New ($arg)");
    is ($csv->record_number, 0, "start");
    if ($arg) {
	is_deeply ([ $csv->header   ($fh, \%attr) ], $dta[0], "Header") if $arg;
	is ($csv->record_number, 1, "first data-record");
	is_deeply ($csv->getline_hr ($fh), $dth[$_], "getline $_") for 0..$#dth;
	}
    else {
	is_deeply ($csv->getline    ($fh), $dta[$_], "getline $_") for 0..$#dta;
	}
    is ($csv->record_number, 3, "done");
    close $fh;
    }


