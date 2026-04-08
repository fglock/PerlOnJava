#!/usr/bin/perl

use strict;
$^W = 1;

 use Test::More tests => 316;
#use Test::More "no_plan";

my %err;

BEGIN {
    $ENV{PERL_TEXT_CSV} = $ENV{TEST_PERL_TEXT_CSV} || 0;
    require_ok "Text::CSV";
    plan skip_all => "Cannot load Text::CSV" if $@;
    require "./t/util.pl";

    open my $fh, "<", "lib/Text/CSV_PP.pm" or die "Cannot read error messages from PP\n";
    while (<$fh>) {
        m/^\s+([0-9]{4}) => "([^"]+)"/ and $err{$1} = $2;
	}
    close $fh;
    }

my $tfn = "_80test.csv"; END { -f $tfn and unlink $tfn; }
$| = 1;

my $csv = Text::CSV->new ();
is (Text::CSV::error_diag (), "",	"Last failure for new () - OK");
is_deeply ([ $csv->error_diag ], [ 0, "", 0, 0, 0, 0 ], "OK in list context");

sub parse_err {
    my ($n_err, $p_err, $r_err, $f_err, $str) = @_;
    my $s_err = $err{$n_err};
    my $STR = _readable ($str);
    is ($csv->parse ($str), 0,	"$n_err - Err for parse ('$STR')");
    is ($csv->error_diag () + 0, $n_err, "$n_err - Diag in numerical context");
    is ($csv->error_diag (),     $s_err, "$n_err - Diag in string context");
    my ($c_diag, $s_diag, $p_diag, $r_diag, $f_diag) = $csv->error_diag ();
    is ($c_diag, $n_err,	"$n_err - Num diag in list context");
    is ($s_diag, $s_err,	"$n_err - Str diag in list context");
    is ($p_diag, $p_err,	"$n_err - Pos diag in list context");
    is ($r_diag, $r_err,	"$n_err - Rec diag in list context");
    is ($f_diag, $f_err,	"$n_err - Fld diag in list context");
    } # parse_err

parse_err 2023, 19,  1, 2, qq{2023,",2008-04-05,"Foo, Bar",\n}; # "

$csv = Text::CSV->new ({ escape_char => "+", eol => "\n" });
is ($csv->error_diag (), "",		"No errors yet");

parse_err 2010,  3,  1, 1, qq{"x"\r};
parse_err 2011,  4,  2, 1, qq{"x"x};

parse_err 2021,  2,  3, 1, qq{"\n"};
parse_err 2022,  2,  4, 1, qq{"\r"};
parse_err 2025,  2,  5, 1, qq{"+ "};
parse_err 2026,  2,  6, 1, qq{"\0 "};
parse_err 2027,  1,  7, 1,   '"';
parse_err 2031,  1,  8, 1, qq{\r };
parse_err 2032,  2,  9, 1, qq{ \r};
parse_err 2034,  4, 10, 2, qq{1, "bar",2};
parse_err 2037,  1, 11, 1, qq{\0 };

{   my @warn;
    local $SIG{__WARN__} = sub { push @warn => @_ };
    $csv->error_diag ();
    ok (@warn == 1, "Got error message");
    like ($warn[0], qr{^# CSV_(?:PP|XS) ERROR: 2037 - EIF}, "error content");
    }

is ($csv->eof, "", "No EOF");
$csv->SetDiag (2012);
is ($csv->eof, 1,  "EOF caused by 2012");

is (Text::CSV->new ({ ecs_char => ":" }), undef, "Unsupported option");

{   my @warn;
    local $SIG{__WARN__} = sub { push @warn => @_ };
    Text::CSV::error_diag ();
    ok (@warn == 1, "Error_diag in void context ::");
    like ($warn[0], qr{^# CSV_(?:PP|XS) ERROR: 1000 - INI}, "error content");
    }
{   my @warn;
    local $SIG{__WARN__} = sub { push @warn => @_ };
    Text::CSV->error_diag ();
    ok (@warn == 1, "Error_diag in void context ->");
    like ($warn[0], qr{^# CSV_(?:PP|XS) ERROR: 1000 - INI}, "error content");
    }

{   my @warn;
    local $SIG{__WARN__} = sub { push @warn => @_ };
    is (Text::CSV->new ({ auto_diag => 0, ecs_char => ":" }), undef,
	"Unsupported option");
    ok (@warn == 0, "Error_diag in from new ({ auto_diag => 0})");
    }
{   my @warn;
    local $SIG{__WARN__} = sub { push @warn => @_ };
    is (Text::CSV->new ({ auto_diag => 1, ecs_char => ":" }), undef,
	"Unsupported option");
    ok (@warn == 1, "Error_diag in from new ({ auto_diag => 1})");
    like ($warn[0], qr{^# CSV_(?:PP|XS) ERROR: 1000 - INI}, "error content");
    }

is (Text::CSV::error_diag (), "INI - Unknown attribute 'ecs_char'",
					"Last failure for new () - FAIL");
is (Text::CSV->error_diag (), "INI - Unknown attribute 'ecs_char'",
					"Last failure for new () - FAIL");
is (Text::CSV::error_diag (bless {}, "Foo"), "INI - Unknown attribute 'ecs_char'",
					"Last failure for new () - FAIL");
$csv->SetDiag (1000);
is (0 + $csv->error_diag (), 1000,			"Set error NUM");
is (    $csv->error_diag (), "INI - constructor failed","Set error STR");
$csv->SetDiag (0);
is (0 + $csv->error_diag (),    0,			"Reset error NUM");
is (    $csv->error_diag (),   "",			"Reset error STR");

ok (1, "Test auto_diag");
$csv = Text::CSV->new ({ auto_diag => 1 });
{   my @warn;
    local $SIG{__WARN__} = sub { push @warn => @_ };
    is ($csv->{_RECNO}, 0, "No records read yet");
    is ($csv->parse ('"","'), 0, "1 - bad parse");
    ok (@warn == 1, "1 - One error");
    like ($warn[0], qr '^# CSV_(?:PP|XS) ERROR: 2027 -', "1 - error message");
    is ($csv->{_RECNO}, 1, "One record read");
    }
{   my @warn;
    local $SIG{__WARN__} = sub { push @warn => @_ };
    is ($csv->diag_verbose (3), 3, "Set diag_verbose");
    is ($csv->parse ('"","'), 0, "1 - bad parse");
    ok (@warn == 1, "1 - One error");
    @warn = split m/\n/ => $warn[0];
    ok (@warn == 3, "1 - error plus two lines");
    like ($warn[0], qr '^# CSV_(?:PP|XS) ERROR: 2027 -', "1 - error message");
    like ($warn[1], qr '^"","',                   "1 - input line");
    like ($warn[2], qr '^   \^',                 "1 - position indicator");
    is ($csv->{_RECNO}, 2, "Another record read");
    }
{   ok ($csv->{auto_diag} = 2, "auto_diag = 2 to die");
    eval { $csv->parse ('"","') };
    like ($@, qr '^# CSV_(?:PP|XS) ERROR: 2027 -', "2 - error message");
    }

{   my @warn;
    local $SIG{__WARN__} = sub { push @warn => @_ };

    # Invalid error_input calls
    is (Text::CSV::error_input (undef), undef, "Bad error_input call");
    is (Text::CSV::error_input (""),    undef, "Bad error_input call");
    is (Text::CSV::error_input ([]),    undef, "Bad error_input call");
    is (Text::CSV->error_input,         undef, "Bad error_input call");

    ok (my $csv = Text::CSV->new (), "new for cache diag");
    $csv->_cache_diag ();
    ok (@warn == 1, "Got warn");
    is ($warn[0], "CACHE: invalid\n", "Uninitialized cache");

    @warn = ();
    ok ($csv->parse ("1"), "parse"); # initialize cache
    $csv->_cache_set (987, 10);
    ok (@warn == 1, "Got warn");
    is ($warn[0], "Unknown cache index 987 ignored\n", "Ignore bad cache calls");

    is ($csv->parse ('"'), 0, "Bad parse");
    is ($csv->error_input, '"', "Error input");
    ok ($csv->_cache_set (34, 0), "Reset error input (dangerous!)");
    is ($csv->error_input, '"', "Error input not reset");
    }

{   my $csv = Text::CSV->new ();
    ok ($csv->parse (q{1,"abc"}), "Valid parse");
    is ($csv->error_input (), undef, "Undefined error_input");
    is ($csv->{_ERROR_INPUT}, undef, "Undefined error_input");
    }

foreach my $spec (
	undef,		# No spec at all
	"",		# No spec at all
	"row=0",	# row > 0
	"col=0",	# col > 0
	"cell=0",	# cell = r,c
	"cell=0,0",	# TL col > 0
	"cell=1,0",	# TL row > 0
	"cell=1,1;0,1",	# BR col > 0
	"cell=1,1;1,0",	# BR row > 0
	"row=*",	# * only after n-
	"col=3-1",	# to >= from
	"cell=4,1;1",	# cell has no ;
	"cell=3,3-2,1",	# bottom-right should be right to and below top-left
	"cell=3,3-2,*",	# bottom-right should be right to and below top-left
	"cell=3,3-4,1",	# bottom-right should be right to and below top-left
	"cell=3,3-*,1",	# bottom-right should be right to and below top-left
	"cell=1,*",	# * in single cell col
	"cell=*,1",	# * in single cell row
	"cell=*,*",	# * in single cell row and column
	"cell=1,*-8,9",	# * in cell range top-left cell col
	"cell=*,1-8,9",	# * in cell range top-left cell row
	"cell=*,*-8,9",	# * in cell range top-left cell row and column
	"row=/",	# illegal character
	"col=4;row=3",	# cannot combine rows and columns
	) {
    my $csv = Text::CSV->new ();
    my $r;
    eval { $r = $csv->fragment (undef, $spec); };
    is ($r, undef, "Cannot do fragment with bad RFC7111 spec");
    my ($c_diag, $s_diag, $p_diag) = $csv->error_diag ();
    is ($c_diag, 2013,	"Illegal RFC7111 spec");
    is ($p_diag, 0,	"Position");
    }

my $diag_file = "_$$.out";
open  EH,     ">&STDERR"      or die "STDERR: $!\n";
open  STDERR, ">", $diag_file or die "STDERR: $!\n";
# Trigger extra output for longer quote and sep
is ($csv->sep   ("--"), "--", "set longer sep");
is ($csv->quote ("^^"), "^^", "set longer quote");
ok ($csv->_cache_diag,	"Cache debugging output");
close STDERR;
open  STDERR, ">&EH"          or die "STDERR: $!\n";
open  EH,     "<", $diag_file or die "STDERR: $!\n";
is (scalar <EH>, "CACHE:\n",	"Title");
while (<EH>) {
    m/^\s+(?:tmp|bptr|cache)\b/ and next;
    like ($_, qr{^  \w+\s+[0-9a-f]+:(?:".*"|\s*[0-9]+)$}, "Content");
    }
close EH;
unlink $diag_file;

{   my $err = "";
    local $SIG{__DIE__} = sub { $err = shift; };
    ok (my $csv = Text::CSV->new, "new");
    eval { $csv->print_hr (*STDERR, {}); };
    is (0 + $csv->error_diag, 3009, "Missing column names");
    ok ($csv->column_names ("foo"), "set columns");
    eval { $csv->print_hr (*STDERR, []); };
    is (0 + $csv->error_diag, 3010, "print_hr needs a hashref");
    }

{   my $csv = Text::CSV->new ({ sep_char => "=" });
    eval { $csv->quote ("::::::::::::::"); };
    is (0 + $csv->error_diag,    0, "Can set quote to something long");
    eval { $csv->quote ("="); };
    is (0 + $csv->error_diag, 1001, "Cannot set quote to current sep");
    }

{   my $csv = Text::CSV->new ({ quote_char => "=" });
    eval { $csv->sep ("::::::::::::::"); };
    is (0 + $csv->error_diag,    0, "Can set sep to something long");
    eval { $csv->sep (undef); };
    is (0 + $csv->error_diag, 1008, "Can set sep to undef");
    eval { $csv->sep (""); };
    is (0 + $csv->error_diag, 1008, "Can set sep to empty");
    eval { $csv->sep ("="); };
    is (0 + $csv->error_diag, 1001, "Cannot set sep to current sep");
    }

{   my $csv = Text::CSV->new;
    eval { $csv->header (undef, "foo"); };
    is (0 + $csv->error_diag, 1014, "Cannot read header from undefined source");
    eval { $csv->header (*STDIN, "foo"); };
    like ($@, qr/^usage:/, "Illegal header call");
    }

{   my $csv = Text::CSV->new;
    foreach my $arg ([], sub {}, Text::CSV->new, {}) {
	eval { $csv->parse ($arg) };
	my @diag = $csv->error_diag;
	is   ($diag[0], 1500, "Invalid parameters (code)");
	like ($diag[1], qr{^PRM - Invalid/unsupported argument}, "Invalid parameters (msg)");
	}
    }

SKIP: {
    $] < 5.008 and skip qq{$] does not support ScalarIO}, 24;
    foreach my $key ({}, sub {}, []) {
	my $csv = Text::CSV->new;
	my $x = eval { $csv->csv (in => \"a,b", key => $key) };
	is ($x, undef, "Invalid key");
	my @diag = $csv->error_diag;
	is ($diag[0], 1501, "Invalid key type");
	}

    {   my $csv = Text::CSV->new;
	my $x = eval { $csv->csv (in => \"a,b", value => "b") };
	is ($x, undef, "Value without key");
	my @diag = $csv->error_diag;
	is ($diag[0], 1502, "No key");
	}

    foreach my $val ({}, sub {}, []) {
	my $csv = Text::CSV->new;
	my $x = eval { $csv->csv (in => \"a,b", key => "a", value => $val) };
	is ($x, undef, "Invalid value");
	my @diag = $csv->error_diag;
	is ($diag[0], 1503, "Invalid value type");
	}

    foreach my $ser ("die", 4) {
	ok (my $csv = Text::CSV->new ({ skip_empty_rows => $ser }),
						"New CSV for SER $ser");
	is (eval { $csv->csv (in => \"\n") }, undef,
						"Parse empty line for SER $ser");
	like ($@, qr{^Empty row},		"Message");
	my @diag = $csv->error_diag;
	is   ($diag[0], 2015,			"Empty row");
	like ($diag[1], qr{^ERW - Empty row},	"Error description");
	}
    }

# Issue 19: auto_diag > 1 does not die if ->header () is used
if ($] >= 5.008002) {
    open my $fh, ">", $tfn or die "$tfn: $!\n";
    print $fh qq{foo,bar,baz\n};
    print $fh qq{a,xxx,1\n};
    print $fh qq{b,"xx"xx", 2"\n};
    print $fh qq{c, foo , 3\n};
    close $fh;
    foreach my $h (0, 1) {
	$@ = "";
	my @row;
	my $ok = eval {
	    open  $fh,   "<", $tfn or die "$tfn: $!\n";
	    my $csv = Text::CSV->new ({ auto_diag => 2 });
	    $h and push @row => [ $csv->header ($fh) ];
	    while (my $row = $csv->getline ($fh)) { push @row => $row }
	    close $fh;
	    1;
	    };
	is_deeply (\@row, [[qw(foo bar baz)],[qw(a xxx 1)]], "2 valid rows");
	like ($@, qr '^# CSV_(?:PP|XS) ERROR: 2023 -', "3rd row dies error 2023");
	}
    }
else {
    ok (1, "Test skipped in this version of perl") for 1..4;
    }

1;
