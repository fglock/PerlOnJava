#!/usr/bin/perl

use strict;
$^W = 1;

use Test::More tests => 81;

BEGIN {
    $ENV{PERL_TEXT_CSV} = $ENV{TEST_PERL_TEXT_CSV} || 0;
    require_ok "Text::CSV";
    plan skip_all => "Cannot load Text::CSV" if $@;
    require "./t/util.pl";
    }

$| = 1;

my $tfn = "_77test.csv"; END { -f $tfn and unlink $tfn; }
my @testlist = (
    [ 1, "a", "\x01", "A" ],
    [ 2, "b", "\x02", "B" ],
    [ 3, "c", "\x03", "C" ],
    [ 4, "d", "\x04", "D" ],
    );

my @list;
sub do_tests {
    my $sub = shift;

    $sub->(\@list);
    $sub->(\@list,         0);
    $sub->([@list[2,3]],   2);
    $sub->([],             0,   0);
    $sub->(\@list,         0,  10);
    $sub->([@list[0,1]],   0,   2);
    $sub->([@list[1,2]],   1,   2);
    $sub->([@list[1,2]], 1e0,   2);
    $sub->([@list[1,2]], "1",   2);
    $sub->([@list[1..3]], -3);
    $sub->([@list[1,2]],  -3,   2);
    $sub->([@list[1..3]], -3,   3);

    $sub->([$list[0]],     0,   1);
    $sub->([$list[0]],     0, 1e0);
    $sub->([$list[0]],     0, "1");
    } # do_tests

foreach my $eol ("\n", "\r") {

    @list = @testlist;

    {   ok (my $csv = Text::CSV->new ({ binary => 1, eol => $eol }), "csv out EOL "._readable ($eol));
	open my $fh, ">", $tfn or die "$tfn: $!";
	ok ($csv->print ($fh, $_), "write $_->[0]") for @list;
	close $fh;
	}

    {   ok (my $csv = Text::CSV->new ({ binary => 1 }), "csv in");

	do_tests (sub {
	    my ($expect, @args) = @_;
	    open my $fh, "<", $tfn or die "$tfn: $!";
	    my $s_args = join ", " => @args;
	    is_deeply ($csv->getline_all ($fh, @args), $expect, "getline_all ($s_args)");
	    close $fh;
	    });
	}

    {   ok (my $csv = Text::CSV->new ({ binary => 1 }), "csv in");
	ok ($csv->column_names (my @cn = qw( foo bar bin baz )), "Set column names");
	@list = map { my %h; @h{@cn} = @$_; \%h } @list;

	do_tests (sub {
	    my ($expect, @args) = @_;
	    open my $fh, "<", $tfn or die "$tfn: $!";
	    my $s_args = join ", " => @args;
	    is_deeply ($csv->getline_hr_all ($fh, @args), $expect, "getline_hr_all ($s_args)");
	    close $fh;
	    });
	}

    {   ok (my $csv = Text::CSV->new ({ binary => 1 }), "csv in");
	open my $fh, "<", $tfn or die "$tfn: $!";
	eval { my $row = $csv->getline_hr_all ($fh); };
	is ($csv->error_diag () + 0, 3002, "Use _hr before colnames ()");
	}
    }
