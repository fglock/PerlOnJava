#!/usr/bin/perl

use strict;
$^W = 1;

 use Test::More tests => 75;
#use Test::More "no_plan";

my %err;

BEGIN {
    $ENV{PERL_TEXT_CSV} = $ENV{TEST_PERL_TEXT_CSV} || 0;
    require_ok "Text::CSV";
    plan skip_all => "Cannot load Text::CSV" if $@;
    require "./t/util.pl";

    open my $fh, "<", "lib/Text/CSV_PP.pm" or die "Cannot read error messages from PP\n";
    while (<$fh>) {
	m/^    ([0-9]{4}) => "([^"]+)",/ and $err{$1} = $2;
	}
    close $fh;
    }

my $tfn = "_80test.csv"; END { -f $tfn and unlink $tfn; }
$| = 1;

my $csv = Text::CSV->new ();

{   my $csv = Text::CSV->new ({ strict => 1 });
    ok ($csv->parse ("1,2,3"), "Set strict to 3 columns");
    ok ($csv->parse ("a,b,c"), "3 columns should be correct");
    is ($csv->parse ("3,4"), 0, "Not enough columns");
    is (0 + $csv->error_diag, 2014, "Error set correctly");
    }
{   my $csv = Text::CSV->new ({ strict => 1 });
    ok ($csv->parse ("1,2,3"), "Set strict to 3 columns");
    is ($csv->parse ("3,4,5,6"), 0, "Too many columns");
    is (0 + $csv->error_diag, 2014, "Error set correctly");
    }
{   my $csv = Text::CSV->new ({ strict => 1 });
    open my $fh, ">", $tfn or die "$tfn: $!\n";
    ok ($csv->say ($fh, [ 1, 2, 3 ]), "Write line 1");
    ok ($csv->say ($fh, [ 1, 2, 3 ]), "Write line 2");
    close $fh;
    open    $fh, "<", $tfn or die "$tfn: $!\n";
    ok ((my $r = $csv->getline ($fh)),	"Get line 1 under strict");
    ok ((   $r = $csv->getline ($fh)),	"Get line 2 under strict");
    is ($csv->getline ($fh), undef,	"EOF under strict");
    is (0 + $csv->error_diag, 2012,	"Error is 2012 instead of 2014");
    ok ($csv->eof,			"EOF is set");
    close $fh;
    }
{   my $csv = Text::CSV->new ({ strict => 1 });
    open my $fh, ">", $tfn or die "$tfn: $!\n";
    ok ($csv->say   ($fh, [ 1, 2, 3 ]), "Write line 1");
    ok ($csv->print ($fh, [ 1, 2, 3 ]), "Write line 2 no newline");
    close $fh;
    open    $fh, "<", $tfn or die "$tfn: $!\n";
    ok ((my $r = $csv->getline ($fh)),	"Get line 1 under strict");
    ok ((   $r = $csv->getline ($fh)),	"Get line 2 under strict no newline");
    is ($csv->getline ($fh), undef,	"EOF under strict");
    is (0 + $csv->error_diag, 2012,	"Error is 2012 instead of 2014");
    ok ($csv->eof,			"EOF is set");
    close $fh;
    }
{   my $csv = Text::CSV->new ();
    open my $fh, ">", $tfn or die "$tfn: $!\n";
    ok ($csv->say ($fh, [ 1 .. 3 ]),    "Write line 1 (headers)");
    ok ($csv->say ($fh, [ 1 .. 4 ]),    "Write line 2 (data)");
    close $fh;
    my $aoh = Text::CSV::csv (in => $tfn, headers => "auto");
    is_deeply ($aoh, [{ 1 => 1, 2 => 2, 3 => 3 }], "Column dropped");
    my @e;
    eval {
	local $SIG{__WARN__} = sub { push @e => @_ };
	$aoh = Text::CSV::csv (in => $tfn, headers => "auto", strict => 1);
	};
    is_deeply ($aoh, [],                "Fail under strict");
    is (scalar @e, 1,			"Got error");
    like ($e[0], qr{ 2014 },		"Error 2014");

    open $fh, ">", $tfn or die "$tfn: $!\n";
    ok ($csv->say ($fh, [ 1 .. 4 ]),    "Write line 1 (headers)");
    ok ($csv->say ($fh, [ 1 .. 3 ]),    "Write line 2 (data)");
    close $fh;
    $aoh = Text::CSV::csv (in => $tfn, headers => "auto");
    is_deeply ($aoh, [{ 1 => 1, 2 => 2, 3 => 3, 4 => undef }], "Column added");
    @e = ();
    eval {
	local $SIG{__WARN__} = sub { push @e => @_ };
	$aoh = Text::CSV::csv (in => $tfn, headers => "auto", strict => 1);
	};
    is_deeply ($aoh, [],                "Fail under strict");
    is (scalar @e, 1,			"Got error");
    like ($e[0], qr{ 2014 },		"Error 2014");
    }

foreach my $strict (0, 1) {
    my $csv = Text::CSV->new ({
	binary      => 1,
	comment_str => "#",
	eol         => "\n",
	escape_char => '"',
	quote_char  => '"',
	sep_char    => "|",
	strict      => $strict,
	});

    my $status = $csv->parse ('a|b|"d"');
    is (0 + $csv->error_diag,    0, "No fail under strict = $strict");
    $status = $csv->parse ('a|b|c"d"e');	# Loose unescaped quote
    is (0 + $csv->error_diag, 2034, "Previous error still actual");
    }

open my $fh, ">", $tfn or die "$tfn: $!\n";
print $fh <<"EOC";
1,foo
2,bar,fail
3,baz
4
5,eox
EOC
close $fh;

open $fh, "<", $tfn or die "$tfn: $!\n";
my @rpt;
$csv = Text::CSV->new ({ strict => 1, auto_diag => 1 });
$csv->callbacks (error => sub {
    my ($err, $msg, $pos, $recno, $fldno) = @_;
    if ($err == 2014) {
	push @rpt => [ $recno, $fldno, $pos ];
	$csv->SetDiag (0);
	}
    });
is_deeply ([ $csv->getline_all ($fh), @rpt ],
    [[[ 1, "foo" ], [ 2, "bar", "fail" ], [ 3, "baz" ], [ 4 ], [ 5, "eox" ]],
     [ 2, 3, 12 ], [ 4, 1, 3 ]], "Can catch strict 2014 with \$csv");
close $fh;

open $fh, "<", $tfn or die "$tfn: $!\n";
@rpt = ();
$csv = Text::CSV->new ({ strict => 1, auto_diag => 1, callbacks => {
    error => sub {
	my ($err, $msg, $pos, $recno, $fldno) = @_;
	if ($err == 2014) {
	    push @rpt => [ $recno, $fldno, $pos ];
	    Text::CSV->SetDiag (0);
	    }
	}}});
is_deeply ([ $csv->getline_all ($fh), @rpt ],
    [[[ 1, "foo" ], [ 2, "bar", "fail" ], [ 3, "baz" ], [ 4 ], [ 5, "eox" ]],
     [ 2, 3, 12 ], [ 4, 1, 3 ]], "Can catch strict 2014 with class");
close $fh;

# Under strcict, fail un not enough fields.
# Under non-strict expect the value of the previous record
foreach my $test (
	[ "a,b,c\n" . "d,e,f\n". "g,h\n".   "i,j,k\n",
	  "a,b,c\n" . "d,e,f\n". "g,h,f\n". "i,j,k\n", 2, 5 ],
	[ "a,b,c\n" . "d,e,f\n". "g,h\n"             ,
	  "a,b,c\n" . "d,e,f\n". "g,h,f\n"           , 2, 5 ],
	[ "a,b,c\n" .            "g,h\n".   "i,j,k\n",
	  "a,b,c\n" .            "g,h,c\n". "i,j,k\n", 1, 5 ],
	[ "a,b\n"   . "d,e,f\n". "g,h\n".   "i,j,k\n",
	  "a,b,*\n" . "d,e,f\n". "g,h,f\n". "i,j,k\n", 1, 5 ],
	) {
    my ($dta, $dta0, $err_line, $pos) = @$test;
    open  $fh, ">", $tfn or die "$tfn: $!\n";
    print $fh $dta;
    close $fh;
    my $expect = [ map {[ split m/,/ => $_ ]} grep m/\S/ => split "\n" => $dta0 ];
    foreach my $strict (0, 1) {
	open $fh, "<", $tfn or die "$tfn: $!\n";
	my $csv = Text::CSV->new ({ strict => $strict });
	my ($r1, $r2, $r3) = ("-", "+", "*");
	$csv->bind_columns (\($r1, $r2, $r3));
	my @out;
	eval {
	    while ($csv->getline ($fh)) {
		push @out => [ $r1, $r2, $r3 ];
		}
	    };
	close $fh;
	my @err = $csv->error_diag;
	if ($strict) {
	    is ($err[0], 2014, "ENF");
	    splice @$expect, $err_line;
	    }
	else {
	    is ($err[0], 2012, "EOF");
	    }
	is_deeply (\@out, $expect, "Bound + strict = $strict");
	}
    }

{   ok (my $csv = Text::CSV->new ({ strict => 1 }), "Issue#58 data first");
    ok ($csv->column_names (qw( A B C )), "Expect 3 colums");
    is_deeply ($csv->getline_hr (*DATA), { A => 1, B => 2, C => 42 }, "Stream OK");
    ok ($csv->parse ("1,2,42"), "Parse");
    is_deeply ([ $csv->fields ], [ 1, 2, 42 ], "Parse OK");
    is ($csv->parse ("2,42"), 0, "Parse not enough");
    my @err = $csv->error_diag; # error-code, str, pos, rec, fld
    is ($err[0], 2014, "Error 2014");
    is ($err[4], 2,    "Just got 2");
    }
{   ok (my $csv = Text::CSV->new ({ strict => 1 }), "Issue#58 no data first");
    ok ($csv->column_names (qw( A B C )), "Expect 3 colums");
    is ($csv->parse ("2,42"), 0, "Parse not enough");
    my @err = $csv->error_diag; # error-code, str, pos, rec, fld
    is ($err[0], 2014, "Error 2014");
    is ($err[4], 2,    "Just got 2");
    }
{   ok (my $csv = Text::CSV->new ({ strict => 1 }), "Issue#62 no data first");
    my $tf = "issue-62-$$.csv";
    END { -e $tf and unlink $tf }
    open my $fh, ">", $tf;
    print   $fh "A,B\n1,2\n";
    close   $fh;
    open    $fh, "<", $tf;
    ok (my @col = @{$csv->getline ($fh)}, "Get header");
    my $val = {};
    ok ($csv->bind_columns (\@{$val}{@col}), "Bind columns");
    ok ($csv->getline ($fh), "Values into bound hash entries");
    my @err = $csv->error_diag; # error-code, str, pos, rec, fld
    is ($err[0], 0, "No error 2014");
    is_deeply ($val, { A => 1, B => 2 }, "Content");
    close   $fh;
    unlink  $tf;
    }
__END__
1,2,42
