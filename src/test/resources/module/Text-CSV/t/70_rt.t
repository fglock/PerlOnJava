#!/usr/bin/perl

use strict;
$^W = 1;

#use Test::More "no_plan";
 use Test::More tests => 20469;
 use Config;

BEGIN {
    $ENV{PERL_TEXT_CSV} = $ENV{TEST_PERL_TEXT_CSV} || 0;
    use_ok "Text::CSV", ();
    plan skip_all => "Cannot load Text::CSV" if $@;
    require "./t/util.pl";
    }

my $tfn = "_70test.csv"; END { unlink $tfn, "_$tfn"; }
my $ebcdic = $Config{ebcdic};

my ($rt, %input, %desc);
while (<DATA>) {
    if (s/^«(x?[0-9]+)»\s*-?\s*//) {
	chomp;
	$rt = $1;
	$desc{$rt} = $_;
	next;
	}
    s/\\([0-7]{1,3})/chr oct $1/ge;
    push @{$input{$rt}}, $_;
    }

# Regression Tests based on RT reports

{   # http://rt.cpan.org/Ticket/Display.html?id=24386
    $rt = 24386; # \t doesn't work in _XS, works in _PP
    my @lines = @{$input{$rt}};

    ok (my $csv = Text::CSV->new ({ sep_char => "\t" }), "RT-$rt: $desc{$rt}");
    is ($csv->sep_char, "\t", "sep_char = TAB");
    foreach my $line (0 .. $#lines) {
	ok ($csv->parse ($lines[$line]), "parse line $line");
	ok (my @fld = $csv->fields, "Fields for line $line");
	is (scalar @fld, 25, "Line $line has 25 fields");
	# print STDERR "# $fld[2] - $fld[3]\t- $fld[4]\n";
	}
    }

{   # http://rt.cpan.org/Ticket/Display.html?id=21530
    $rt = 21530; # getline () does not return documented value at end of
    		 # filehandle IO::Handle  was first released with perl 5.00307
    open my $fh, ">", $tfn or die "$tfn: $!\n";
    print   $fh @{$input{$rt}};
    close   $fh;
    ok (my $csv = Text::CSV->new ({ binary => 1 }), "RT-$rt: $desc{$rt}");
    open    $fh, "<", $tfn or die "$tfn: $!\n";
    my $row;
    foreach my $line (1 .. 5) {
	ok ($row = $csv->getline ($fh), "getline $line");
	is (ref $row, "ARRAY", "is arrayref");
	is ($row->[0], $line, "Line $line");
	}
    ok (eof $fh, "EOF");
    is ($row = $csv->getline ($fh), undef, "getline EOF");
    close  $fh;
    unlink $tfn;
    }

{   # http://rt.cpan.org/Ticket/Display.html?id=21530
    $rt = 18703; # Fails to use quote_char of '~'
    my ($csv, @fld);
    ok ($csv = Text::CSV->new ({ quote_char => "~" }), "RT-$rt: $desc{$rt}");
    is ($csv->quote_char, "~", "quote_char is '~'");

    ok ($csv->parse ($input{$rt}[0]), "Line 1");
    ok (@fld = $csv->fields, "Fields");
    is (scalar @fld, 1, "Line 1 has only one field");
    is ($fld[0], "Style Name", "Content line 1");

    # The line has invalid escape. the escape should only be
    # used for the special characters
    ok (!$csv->parse ($input{$rt}[1]), "Line 2");
    }

{   # http://rt.cpan.org/Ticket/Display.html?id=15076
    $rt = 15076; # escape_char before characters that do not need to be escaped.
    my ($csv, @fld);
    ok ($csv = Text::CSV->new ({
	sep_char		=> ";",
	escape_char		=> "\\",
	allow_loose_escapes	=> 1,
	}), "RT-$rt: $desc{$rt}");

    ok ($csv->parse ($input{$rt}[0]), "Line 1");
    ok (@fld = $csv->fields, "Fields");
    is (scalar @fld, 2, "Line 1 has two fields");
    is ($fld[0], "Example", "Content field 1");
    is ($fld[1], "It's an apostrophee", "Content field 2");
    }

{   # http://rt.cpan.org/Ticket/Display.html?id=34474
    $rt = 34474; # wish: integrate row-as-hashref feature from Parse::CSV
    open my $fh, ">", $tfn or die "$tfn: $!\n";
    print   $fh @{$input{$rt}};
    close   $fh;
    ok (my $csv = Text::CSV->new (),		"RT-$rt: $desc{$rt}");
    is ($csv->column_names, undef,		"No headers yet");
    open    $fh, "<", $tfn or die "$tfn: $!\n";
    my $row;
    ok ($row = $csv->getline ($fh),		"getline headers");
    is ($row->[0], "code",			"Header line");
    $csv->column_names (@$row);
    is_deeply ([ $csv->column_names ], [ @$row ], "Keys set");
    while (my $hr = $csv->getline_hr ($fh)) {
	ok (exists $hr->{code},			"Line has a code field");
	like ($hr->{code}, qr/^[0-9]+$/,	"Code is numeric");
	ok (exists $hr->{name},			"Line has a name field");
	like ($hr->{name}, qr/^[A-Z][a-z]+$/,	"Name");
	}
    close  $fh;
    unlink $tfn;
    }

{   # http://rt.cpan.org/Ticket/Display.html?id=38960
    $rt = 38960; # print () on invalid filehandle warns and returns success
    open my $fh, ">", $tfn or die "$tfn: $!\n";
    print   $fh "";
    close   $fh;
    my $err = "";
    open    $fh, "<", $tfn or die "$tfn: $!\n";
    ok (my $csv = Text::CSV->new (),		"RT-$rt: $desc{$rt}");
    local $SIG{__WARN__} = sub { $err = "Warning" };
    ok (!$csv->print ($fh, [ 1 .. 4 ]),		"print ()");
    is ($err, "Warning",			"IO::Handle triggered a warning");
    is (($csv->error_diag)[0], 2200,		"error 2200");
    close  $fh;
    unlink $tfn;
    }

{   # http://rt.cpan.org/Ticket/Display.html?id=40507
    $rt = 40507; # Parsing fails on escaped null byte
    ok (my $csv = Text::CSV->new ({ binary => 1 }), "RT-$rt: $desc{$rt}");
    my $str = $input{$rt}[0];
    ok ($csv->parse ($str),		"parse () correctly escaped NULL");
    is_deeply ([ $csv->fields ],
	[ qq{Audit active: "TRUE \0},
	  qq{Desired:},
	  qq{Audit active: "TRUE \0} ], "fields ()");
    $str = $input{$rt}[1];
    is ($csv->parse ($str), 0,		"parse () badly escaped NULL");
    my @diag = $csv->error_diag;
    is ($diag[0], 2023,			"Error 2023");
    is ($diag[2],   23,			"Position 23");
    $csv->allow_loose_escapes (1);
    ok ($csv->parse ($str),		"parse () badly escaped NULL");
    }

{   # http://rt.cpan.org/Ticket/Display.html?id=42642
    $rt = 42642; # failure on unusual quote/sep values
    SKIP: {
	$] < 5.008002 and skip "UTF8 unreliable in perl $]", 6;

	open my $fh, ">:raw", $tfn or die "$tfn: $!\n";
	print   $fh @{$input{$rt}};
	close   $fh;
	my ($sep, $quo) = $ebcdic ? ("\x3c", "\x8e") : ("\x14", "\xfe");
	chop ($_ = "$_\x{20ac}") for $sep, $quo;
	ok (my $csv = Text::CSV->new ({ binary => 1, sep_char => $sep }), "RT-$rt: $desc{$rt}");
	ok ($csv->quote_char ($quo), "Set quote_char");
	open    $fh, "<:raw", $tfn or die "$tfn: $!\n";
	ok (my $row = $csv->getline ($fh),	"getline () with decode sep/quo");
	$csv->error_diag ();
	close  $fh;
	unlink $tfn;
	is_deeply ($row, [qw( DOG CAT WOMBAT BANDERSNATCH )], "fields ()");
	ok ($csv->parse ($input{$rt}[1]),	"parse () with decoded sep/quo");
	is_deeply ([ $csv->fields ], [ 0..3 ],	"fields ()");
	}
    }

{   # http://rt.cpan.org/Ticket/Display.html?id=43927
    $rt = 43927; # Is bind_columns broken or am I using it wrong?
    open my $fh, ">", $tfn or die "$tfn: $!\n";
    print   $fh @{$input{$rt}};
    close   $fh;
    my ($c1, $c2);
    ok (my $csv = Text::CSV->new ({ binary => 1 }), "RT-$rt: $desc{$rt}");
    ok ($csv->bind_columns (\$c1, \$c2), "bind columns");
    open    $fh, "<", $tfn or die "$tfn: $!\n";
    ok (my $row = $csv->getline ($fh), "getline () with bound columns");
    $csv->error_diag ();
    close  $fh;
    unlink $tfn;
    is_deeply ($row, [], "should return empty ref");
    is_deeply ([ $c1, $c2], [ 1, 2 ], "fields ()");
    }

{   # http://rt.cpan.org/Ticket/Display.html?id=44402
    $rt = 44402; # Unexpected results parsing tab-separated spaces
    open my $fh, ">", $tfn or die "$tfn: $!\n";
    my @ws = ("", " ", "  ");
    foreach my $f1 (@ws) {
	foreach my $f2 (@ws) {
	    foreach my $f3 (@ws) {
		print $fh "$f1\t$f2\t$f3\r\n";
		}
	    }
	}
    close $fh;

    my $csv;
    ok ($csv = Text::CSV->new ({
	sep_char => "\t",
	}), "RT-$rt: $desc{$rt}");
    open $fh, "<", $tfn or die "$tfn: $!\n";
    while (my $row = $csv->getline ($fh)) {
	ok ($row, "getline $.");
	my @row = @$row;
	is ($#row, 2, "Got 3 fields");
	like ($row[$_], qr{^ *$}, "field $_ with only spaces") for 0..2;
	}
    ok ($csv->eof, "read complete file");
    close $fh;

    ok ($csv = Text::CSV->new ({
	sep_char         => "\t",
	allow_whitespace => 1,
	}), "RT-$rt: $desc{$rt}");
    open $fh, "<", $tfn or die "$tfn: $!\n";
    while (my $row = $csv->getline ($fh)) {
	ok ($row, "getline $.");
	my @row = @$row;
	is ($#row, 2, "Got 3 fields");
	is ($row[$_], "", "field $_ empty") for 0..2;
	}
    ok ($csv->eof, "read complete file");
    close  $fh;
    unlink $tfn;

    ok ($csv->parse ("  \t  \t  "), "parse ()");
    is_deeply ([$csv->fields],["","",""],"3 empty fields");
    }

{   # Detlev reported an inconsistent difference between _XS and _PP
    $rt = "x1000";
    open my $fh, ">", $tfn or die "$tfn: $!\n";
    print   $fh @{$input{$rt}};
    close   $fh;
    my ($c1, $c2);
    ok (my $csv = Text::CSV->new ({
	binary      => 1, 
	eol         => "\n", 
	sep_char    => "\t",
	escape_char => undef,
	quote_char  => undef,
	binary      => 1 }), "RT-$rt: $desc{$rt}");
    open $fh, "<", $tfn or die "$tfn: $!\n";
    for (1 .. 4) {
	ok (my $row = $csv->getline ($fh), "getline ()");
	is (scalar @$row, 27, "Line $_: 27 columns");
	}
    for (5 .. 6) {
	ok (my $row = $csv->getline ($fh), "getline ()");
	is (scalar @$row,  1, "Line $_:  1 column");
	}
    $csv->error_diag ();
    close  $fh;
    unlink $tfn;
    }

{   # Ruslan reported a case where only Text::CSV_PP misbehaved (regression test)
    $rt = "x1001";
    open my $fh, ">", $tfn or die "$tfn: $!\n";
    print   $fh @{$input{$rt}};
    close   $fh;
    my ($c1, $c2);
    ok (my $csv = Text::CSV->new (), "RT-$rt: $desc{$rt}");
    open    $fh, "<", $tfn or die "$tfn: $!\n";
    for (1 .. 4) {
	ok (my $row = $csv->getline ($fh), "getline ($_)");
	is (scalar @$row, 2, "Line $_: 2 columns");
	my @exp = $_ <= 2 ? ("0", "A") : ("A", "0");
	is_deeply ($row, \@exp, "@exp");
	}
    close  $fh;
    unlink $tfn;
    }

{   # http://rt.cpan.org/Ticket/Display.html?id=58356
    $rt = "58356"; # Incorrect CSV generated if "quote_space => 0"
    ok (my $csv = Text::CSV->new ({
	binary      => 1,
	quote_space => 0 }), "RT-$rt: $desc{$rt}");
    my @list = ("a a", "b,b", "c ,c");
    ok ($csv->combine (@list), "combine ()");
    is ($csv->string, q{a a,"b,b","c ,c"}, "string ()");
    }

{   # http://rt.cpan.org/Ticket/Display.html?id=61525
    $rt = "61525"; # eol not working for values other than "\n"?
    # First try with eol in constructor
    foreach my $eol ("\n", "\r", "!") {
	$/ = "\n";
	my $s_eol = _readable ($eol);
	ok (my $csv = Text::CSV->new ({
	    binary      => 1,
	    sep_char    => ":",
	    quote_char  => '"',
	    escape_char => '"',
	    eol         => $eol,
	    auto_diag   => 1,
	    }), "RT-$rt: $desc{$rt} - eol = $s_eol (1)");

	open my $fh, ">", $tfn or die "$tfn: $!\n";
	print   $fh join $eol => qw( "a":"b" "c":"d" "e":"x!y" "!!":"z" );
	close   $fh;

	open    $fh, "<", $tfn or die "$tfn: $!\n";
	is_deeply ($csv->getline ($fh), [ "a",  "b"   ], "Pair 1");
	is_deeply ($csv->getline ($fh), [ "c",  "d"   ], "Pair 2");
	is_deeply ($csv->getline ($fh), [ "e",  "x!y" ], "Pair 3");
	is_deeply ($csv->getline ($fh), [ "!!", "z"   ], "Pair 4");
	is ($csv->getline ($fh), undef, "no more pairs");
	ok ($csv->eof, "EOF");
	close  $fh;
	unlink $tfn;
	}

    # And secondly with eol as method only if not one of the defaults
    foreach my $eol ("\n", "\r", "!") {
	$/ = "\n";
	my $s_eol = _readable ($eol);
	ok (my $csv = Text::CSV->new ({
	    binary      => 1,
	    sep_char    => ":",
	    quote_char  => '"',
	    escape_char => '"',
	    auto_diag   => 1,
	    }), "RT-$rt: $desc{$rt} - eol = $s_eol (2)");
	$eol eq "!" and $csv->eol ($eol);

	open my $fh, ">", $tfn or die "$tfn: $!\n";
	print   $fh join $eol => qw( "a":"b" "c":"d" "e":"x!y" "!!":"z" );
	close   $fh;

	open    $fh, "<", $tfn or die "$tfn: $!\n";
	is_deeply ($csv->getline ($fh), [ "a",  "b"   ], "Pair 1");
	is_deeply ($csv->getline ($fh), [ "c",  "d"   ], "Pair 2");
	is_deeply ($csv->getline ($fh), [ "e",  "x!y" ], "Pair 3");
	is_deeply ($csv->getline ($fh), [ "!!", "z"   ], "Pair 4");
	is ($csv->getline ($fh), undef, "no more pairs");
	ok ($csv->eof, "EOF");
	close  $fh;
	unlink $tfn;
	}
    }

{   # http://rt.cpan.org/Ticket/Display.html?id=74216
    $rt = "74216"; # setting 'eol' affects global input record separator

    open  my $fh, ">", $tfn or die "$tfn: $!\n";
    print    $fh @{$input{$rt}};
    close    $fh;

    my $slurp_check = sub {
	open $fh, "<", $tfn or die "$tfn: $!\n";
	is (scalar @{[<$fh>]}, 4);
	close $fh;
	};

    $slurp_check->();

    my $crlf = "\015\012";
    open my $fhx, ">", "_$tfn" or die "_$tfn: $!\n";
    print   $fhx "a,b,c" . $crlf . "1,2,3" . $crlf;
    close   $fhx;
    open    $fhx, "<", "_$tfn" or die "_$tfn: $!\n";
    my $csv = Text::CSV->new ({ eol => $crlf });
    is_deeply ($csv->getline ($fhx), [qw( a b c )]);
    close  $fhx;
    unlink "_$tfn";

    $slurp_check->();

    {	local $/ = "\n";
	$slurp_check->();
	}
    }

SKIP: {	# http://rt.cpan.org/Ticket/Display.html?id=74220
    $] < 5.008002 and skip "UTF8 unreliable in perl $]", 7;

    $rt = "74220"; # Text::CSV can be made to produce bad strings
    my $csv = Text::CSV->new ({ binary => 1 });

    my $ax = chr (0xfa);
    my $bx = "foo";

    # We set the UTF-8 flag on a string with no funny characters
    utf8::upgrade ($bx);
    is ($bx, "foo", "no funny characters in the string");

    ok (utf8::valid ($ax), "first string correct in Perl");
    ok (utf8::valid ($bx), "second string correct in Perl");

    ok ($csv->combine ($ax, $bx),	"combine ()");
    ok (my $foo = $csv->string (),	"string ()");

    ok (utf8::valid ($foo), "is combined string correct inside Perl?");
    is ($foo, qq{\xfa,foo}, "expected result");
    }

SKIP: {	# http://rt.cpan.org/Ticket/Display.html?id=80680
    (eval { require Encode; $Encode::VERSION } || "0.00") =~ m{^([0-9.]+)};
    $1 < 2.47     and skip "Encode is too old for these tests", 20000;
    $] < 5.008008 and skip "UTF8+Encode unreliable in perl $]", 20000;

    $rt = "80680"; # Text::CSV produces garbage on some data

    my $csv = Text::CSV->new ({ binary => 1 });
    my $txt = "\x{415}\x{43a}\x{438}\x{43d}\x{431}\x{443}\x{440}\x{433}\x{2116}";
    BIG_LOOP: foreach my $n (1 .. 5000) {
	foreach my $e (0 .. 3) {

	    my $data = ("a" x $e) . ($txt x $n);
	    my $enc  = Encode::encode ("UTF-8", $data);
	    my $exp  = qq{1,"$enc"};
	    my $out  = "";
	    open my $fh, ">:encoding(utf-8)", \$out or die "IO: $!\n";
	    $csv->print ($fh, [ 1, $data ]);
	    close $fh;

	    my $l = length ($out);
	    if ($out eq $exp) {
		ok (1, "Buffer boundary check $n/$e ($l)");
		next;
		}

	    is ($out, $exp, "Data $n/$e ($l)");
	    last BIG_LOOP;
	    }
	}
    }

{   # http://rt.cpan.org/Ticket/Display.html?id=81295
    $rt = 81295; # escaped sep_char discarded when only item in unquoted field
    my $csv = Text::CSV->new ({ escape_char => "\\", auto_diag => 1 });
    ok ($csv->parse ($input{$rt}[0]),		"parse without allow_unquoted_escape");
    is_deeply ([ $csv->fields ], [ 1, ",", 3 ], "escaped sep in quoted field");
    $csv->allow_unquoted_escape (1);
    ok ($csv->parse ($input{$rt}[1]),		"parse with allow_unquoted_escape");
    is_deeply ([ $csv->fields ], [ 1, ",", 3 ], "escaped sep in unquoted field");
    }

{   # http://rt.cpan.org/Ticket/Display.html?id=113279
    $rt = 113279; # Failed parse + bind_columns causes memory corruption
    my $csv = Text::CSV->new ();
    is ($csv->parse ($input{$rt}[0]), 0,	"parse invalid content");
    is (0 + $csv->error_diag, 2034,		"Error is kept");
    my $fld;
    ok ($csv->bind_columns (\$fld),		"bound column");
    is ($csv->parse ($input{$rt}[0]), 0,	"parse invalid content to bc");
    is (0 + $csv->error_diag, 2034,		"Error is kept");
    }

{   # http://rt.cpan.org/Ticket/Display.html?id=115953
    $rt = 115953; # Space stripped from middle of field value with allow_whitespace and allow_loose_quotes
    SKIP: {
	$] < 5.006002 and skip "unreliable in perl $]", 2;
	my $csv = Text::CSV->new ({
	    allow_loose_quotes => 1,
	    escape_char        => undef,
	    allow_whitespace   => 1,
	    });
	ok ($csv->parse ($input{$rt}[0]),	"parse valid content");
	is_deeply ([ $csv->fields ], [ q{foo "bar" baz} ], "Data");
	}
    }

{   # http://rt.cpan.org/Ticket/Display.html?id=120655
    $rt = 120655; # bind_columns with strange behavior / length() from old value
    SKIP: {
	$] < 5.008002 and skip "UTF8 unreliable in perl $]", 5;
	my $csv = Text::CSV->new ({ binary => 1 });
	my %row;
	ok ($csv->bind_columns (\$row{c1}),	"Bind columns");
	my $oe = $ebcdic ? "\x8e\x62" : "\xc5\x93";
	ok ($csv->parse ("pr${oe}blem"),	"Parse utf-8 content");
	is (length $row{c1}, 7,			"Length");
	ok ($csv->parse (""),			"Parse empty line");
	is (length $row{c1}, 0,			"Length");
	}
    }

{   # http://rt.cpan.org/Ticket/Display.html?id=123320
    $rt = 123320; # ext::CSV_XS bug w/Mac format files

    SKIP: {
	$] < 5.008001 and skip "unreliable in perl $]", 4;

	open my $fh, ">", $tfn or die "$tfn: $!\n";
	print $fh join "\r" =>
	    q{col1,col2,col3,},
	    q{"One","","Three"},
	    q{"Four","Five and a half","Six"},
	    q{};
	close $fh;

	ok (my $csv = Text::CSV->new ({ auto_diag => 1, eol => "\r", }), "new");

	my @msg;
	local $SIG{__WARN__} = sub { push @msg, @_; };

	open $fh, "<", $tfn  or die "$!\n";
	my @hdr = eval { $csv->header ($fh); };
	is (scalar @hdr,		0,	"Empty field in header");
	is (($csv->error_diag)[0],	1012,	"error 1012");
	close $fh;

	open $fh, ">", $tfn or die "$tfn: $!\n";
	print $fh join "\r" =>
	    q{col1,col2,col3},
	    q{"One","Two","Three"},
	    "";
	close $fh;
	open $fh, "<", $tfn or die "$!\n";
	@hdr = eval { $csv->header ($fh); };
	is_deeply (\@hdr, [qw( col1 col2 col3 )], "Header is ok");
	close $fh;
	}
    }

__END__
«24386» - \t doesn't work in _XS, works in _PP
VIN	StockNumber	Year	Make	Model	MD	Engine	EngineSize	Transmission	DriveTrain	Trim	BodyStyle	CityFuel	HWYFuel	Mileage	Color	InteriorColor	InternetPrice	RetailPrice	Notes	ShortReview	Certified	NewUsed	Image_URLs	Equipment
1HGCM66573A030460	1621HA	2003	HONDA	ACCORD EX V-6	ACCORD	DOHC 16-Valve VTEC	3.0L	5-Speed Automatic		EX V-6	4DR	21	30	70940	Gray	Gray	15983	15983		AutoWeek calls the 2003 model the best Accord yet * Fun to hustle down a twisty road according to Road & Track * Sedan perfection according to Car and Driver * Named on the 2003 Car and Driver Ten Best List * Named a Consumer Guide Best Buy for 2003 *	0	0	http://vin.windowstickers.biz/incoming/w_1HGCM66573A030460_1.JPG, http://vin.windowstickers.biz/incoming/w_1HGCM66573A030460_2.JPG, http://vin.windowstickers.biz/incoming/w_1HGCM66573A030460_3.JPG, http://vin.windowstickers.biz/incoming/w_1HGCM66573A030460_4.JPG, http://vin.windowstickers.biz/incoming/w_1HGCM66573A030460_5.JPG, http://vin.windowstickers.biz/incoming/w_1HGCM66573A030460_6.JPG, http://vin.windowstickers.biz/incoming/w_1HGCM66573A030460_7.JPG, http://vin.windowstickers.biz/incoming/w_1HGCM66573A030460_8.JPG	120-Watt AM/FM Stereo System,3-Point Seat Belts,4-Wheel Double Wishbone Suspension,6-Disc In-Dash Compact Disc Changer,6-Speaker Audio System,8-Way Power Adjustable Driver's Seat,Air Conditioning w/Air-Filtration System,Anti-Lock Braking System,Automatic-Up/Down Driver's Window,Center Console Armrest w/Storage,Child Safety Rear Door Locks,Cruise Control,Driver & Front Passenger Dual-Stage Airbags,Electronic Remote Trunk Release,Emergency Trunk Release,Fold-Down Rear Seat Center Armrest,Fold-Down Rear Seatback w/Lock,Front Seat Side-Impact Airbags,Immobilizer Theft Deterrent System,LATCH Lower Anchor & Tethers For Children,Power Driver's Seat Height Adjustment,Power Exterior Mirrors,Power Moonroof w/Tilt Feature,Power Windows & Door Locks,Power-Assisted 4-Wheel Disc Brakes,Rear Window Defroster w/Timer,Remote Keyless Entry System w/Window Control,Security System,Tilt & Telescopic Steering Column,Traction Control System,Variable Intermittent Windshield Wipers,Variable-Assist Power Rack & Pinion Steering
1FTRW12W66KA65476	4110J	2006	FORD	F-150 XLT CREW 5.5SB 4X2	F-150	SOHC Triton V8	4.6L	4-Speed Automatic	4X2	XLT	CREW 5.5SB	15	19	20334	Black	Gray	22923	22923		Named a Consumer Guide 2005 & 2006 Best Buy * Named Best Pickup by Car and Driver * The Detroit Free Press calls F-150 the best pickup truck ever * The Detroit News calls F-150 the best America has to offer *	0	0	http://vin.windowstickers.biz/incoming/w_1FTRW12W66KA65476_1.JPG, http://vin.windowstickers.biz/incoming/w_1FTRW12W66KA65476_2.JPG, http://vin.windowstickers.biz/incoming/w_1FTRW12W66KA65476_3.JPG, http://vin.windowstickers.biz/incoming/w_1FTRW12W66KA65476_4.JPG, http://vin.windowstickers.biz/incoming/w_1FTRW12W66KA65476_5.JPG, http://vin.windowstickers.biz/incoming/w_1FTRW12W66KA65476_6.JPG, http://vin.windowstickers.biz/incoming/w_1FTRW12W66KA65476_7.JPG, http://vin.windowstickers.biz/incoming/w_1FTRW12W66KA65476_8.JPG, http://vin.windowstickers.biz/incoming/w_1FTRW12W66KA65476_9.JPG	4-Pin Trailer Tow Connector,Air Conditioning,AM/FM Stereo w/Single Compact Disc Player,Auxiliary Power Outlets,Cargo Box Light & Tie-Downs,Child Safety Seat Lower Anchors & Tether Anchors,Crash Severity Sensor,Cruise Control,Dual-Stage Driver & Front-Right Passenger Airbags,Electronic Brake Force Distribution,Exterior Temperature & Compass Display,Fail-Safe Engine Cooling System,Front Dome Light w/Integrated Map Lights,Front Power Points,Front Seat Personal Safety System,Front-Passenger Sensing System,Manual Day/Night Interior Rearview Mirror,Oil Pressure & Coolant Temperature Gauges,Power 4-Wheel Disc Anti-Lock Brakes,Power Door Locks,Power Exterior Mirrors,Power Front Windows w/One-Touch Driver Side,Power Rack & Pinion Steering,Remote Keyless Entry System,Removable Tailgate w/Key Lock,Securilock Passive Anti-Theft System,Spare Tire w/Wheel Lock,Speed-Dependent Interval Windshield Wipers,Tailgate Assist System,Tilt Steering Wheel,Visors w/Covered Vanity Mirrors
5GZCZ23D03S826657	2111A	2003	SATURN	VUE BASE FWD	VUE	DOHC 4-cylinder	2.2L	5-Speed Manual	FWD	BASE	5DR	23	28	74877	Silver	Gray	11598	11598		Edmunds 2003 Buyer's Guide calls Vue a well-thought-out and capable mini sport utility vehicle, with large doors for ease of entry and exit, extensive cabin space and excellent crash test scores *	0	0	http://vin.windowstickers.biz/incoming/w_5GZCZ23D03S826657_1.JPG, http://vin.windowstickers.biz/incoming/w_5GZCZ23D03S826657_2.JPG, http://vin.windowstickers.biz/incoming/w_5GZCZ23D03S826657_3.JPG, http://vin.windowstickers.biz/incoming/w_5GZCZ23D03S826657_4.JPG, http://vin.windowstickers.biz/incoming/w_5GZCZ23D03S826657_5.JPG, http://vin.windowstickers.biz/incoming/w_5GZCZ23D03S826657_6.JPG, http://vin.windowstickers.biz/incoming/w_5GZCZ23D03S826657_7.JPG, http://vin.windowstickers.biz/incoming/w_5GZCZ23D03S826657_8.JPG	70/30 Split Folding Rear Seatback,AM/FM Stereo System,Center Console w/Storage,Center High-Mounted Rear Stop Light,CFC-Free Air Conditioning,Cloth Upholstery,Daytime Running Lights,Dent-Resistant Polymer Body Panels,Distributorless Ignition System,Driver & Front Passenger Frontal Airbags,Electric Power Rack-And-Pinion Steering,Fold-Flat Front Passenger Seat,Front & Rear Crumple Zones,Front & Rear Cup Holders,Front Bucket Seats,Front-Wheel Drive,Independent Front & Rear Suspension,Interval Rear Window Wiper/Washer,Interval Windshield Wipers,LATCH Child Safety Seat Anchor System,Platinum-Tipped Spark Plugs,Power Front Disc/Rear Drum Brakes,Rear Privacy Glass,Rear Window Defogger,Remote Rear Liftgate Release,Roof Rack,Sequential Fuel Injection,Side-Impact Door Beams,Tachometer,Theft-Deterrent System,Tilt Adjustable Steering Wheel,Visor Vanity Mirrors
1FMZU67K15UB18754	4067T	2005	FORD	EXPLORER SPORT TRAC XLT 4X2	EXPLORER SPORT TRAC	Flex Fuel SOHC V6	4.0L	5-Speed Automatic	4X2	XLT	4DR	16	21	12758	Maroon	Gray	20995	20995		Consumer Guide 2005 reports Sport Trac offers more passenger space than other crew-cab pick-ups and is a good choice as a multipurpose vehicle * Consumer Guide 2005 credits Sport Trac with good in-cabin storage *	0	0	http://vin.windowstickers.biz/incoming/w_1FMZU67K15UB18754_1.JPG, http://vin.windowstickers.biz/incoming/w_1FMZU67K15UB18754_2.JPG, http://vin.windowstickers.biz/incoming/w_1FMZU67K15UB18754_3.JPG, http://vin.windowstickers.biz/incoming/w_1FMZU67K15UB18754_4.JPG, http://vin.windowstickers.biz/incoming/w_1FMZU67K15UB18754_5.JPG, http://vin.windowstickers.biz/incoming/w_1FMZU67K15UB18754_6.JPG, http://vin.windowstickers.biz/incoming/w_1FMZU67K15UB18754_7.JPG	3-Point Front & Rear Seatbelts,4-Speaker Audio System,Air Conditioning,AM/FM Stereo w/Compact Disc Player,Belt-Minder Safety Belt Reminder System,Child Safety Rear Door Locks,Cloth Upholstery,Cruise Control,Driver & Front Passenger Airbags,Driver Door Keyless Entry Keypad,Headlights-On Alert Chime,Height-Adjustable Front Seatbelts,LATCH Child Seat Lower Anchors & Tether Anchors,Locking Tailgate,Low-Back Front Bucket Seats,Lower Bodyside Moldings,Manual Day/Night Interior Rearview Mirror,Power 4-Wheel Disc Anti-Lock Brakes,Power Door Locks,Power Exterior Mirrors,Power Rack & Pinion Steering,Power Rear Window w/Anti-Pinch,Power Windows w/Driver One-Touch Down,Remote Keyless Entry System,Roof Rails,Securilock Passive Anti-Theft System,Side-Intrusion Door Beams,Sirius Satellite Radio/MP3 Capability,Solar-Tinted Glass Windows,Speed-Sensitive Intermittent Windshield Wipers,Tachometer,Tilt Steering Wheel
1J4GK48K96W108753	4068T	2006	JEEP	LIBERTY SPORT 4X2	LIBERTY	SOHC 12-valve V6	3.7L	4-Speed Automatic	4X2	SPORT	5DR	17	22	12419	Silver	Gray	16999	16999		Named on the Automobile Magazine 50 Great New Cars List * Motor Trend reports Liberty fulfills the original go-anywhere mission of SUVs without fail or compromise * A Consumer Guide 2005 & 2006 Recommended Buy *	0	0	http://vin.windowstickers.biz/incoming/w_1J4GK48K96W108753_1.JPG, http://vin.windowstickers.biz/incoming/w_1J4GK48K96W108753_2.JPG, http://vin.windowstickers.biz/incoming/w_1J4GK48K96W108753_3.JPG, http://vin.windowstickers.biz/incoming/w_1J4GK48K96W108753_4.JPG, http://vin.windowstickers.biz/incoming/w_1J4GK48K96W108753_5.JPG, http://vin.windowstickers.biz/incoming/w_1J4GK48K96W108753_6.JPG, http://vin.windowstickers.biz/incoming/w_1J4GK48K96W108753_7.JPG, http://vin.windowstickers.biz/incoming/w_1J4GK48K96W108753_8.JPG, http://vin.windowstickers.biz/incoming/w_1J4GK48K96W108753_9.JPG	12-Volt Cargo Area Power Outlet,65/35 Split-Folding Rear Bench Seat,6-Speaker Audio System,Advanced Multi-Stage Frontal Airbags,Air Conditioning,All-Wheel Traction Control System,AM/FM Stereo w/Compact Disc Player,Center Console 12-Volt Power Outlet,Child Safety Rear Door Locks,Cloth Sun Visors w/Pull-Out Sunshade,Cloth Upholstery,Coolant Temperature Gauge,Electric Rear Window Defroster,Electronic Stability Program,Enhanced Accident Response System,Halogen Headlights w/Delay-Off Feature,LATCH Child Safety Seat Anchor System,Manual Day/Night Interior Rearview Mirror,Power 4-Wheel Disc Anti-Lock Brake System,Power Door Locks,Power Exterior Mirrors,Power Rack & Pinion Steering,Power Windows w/Front One-Touch Down,Rear Window Wiper/Washer,Remote Keyless Entry System,Roof Side Rails,Sentry Key Engine Immobilizer,Spare Tire Carrier,Tachometer,Tilt Steering Column,Tinted Windshield Glass,Variable Speed Intermittent Windshield Wipers
«21530» - getline () does not return documented value at end of filehandle
1,1,2,3,4,5
2,1,2,3,4,5
3,1,2,3,4,5
4,1,2,3,4,5
5,1,2,3,4,5
«18703» - Fails to use quote_char of '~'
~Style Name~
~5dr Crew Cab 130" WB 2WD LS~
",~"~,~""~,~"""~,,~~,
«15076» - escape_char before characters that do not need to be escaped.
"Example";"It\'s an apostrophee"
«34474» - wish: integrate row-as-hashref feature from Parse::CSV
code,name,price,description
1,Dress,240.00,"Evening gown"
2,Drinks,82.78,"Drinks"
3,Sex,-9999.99,"Priceless"
«38960» - print () on invalid filehandle warns and returns success
«40507» - Parsing fails on escaped null byte
"Audit active: ""TRUE "0","Desired:","Audit active: ""TRUE "0"
"Audit active: ""TRUE "\0","Desired:","Audit active: ""TRUE "\0"
«42642» - failure on unusual quote/sep values
þDOGþþCATþþWOMBATþþBANDERSNATCHþ
þ0þþ1þþ2þþ3þ
«43927» - Is bind_columns broken or am I using it wrong?
1,2
«44402» - Unexpected results parsing tab-separated spaces
«x1000» - Detlev reported inconsistent behavior between XS and PP
ï»¿B:033_02_	-drop, +drop	animal legs	@p 02-033.bmp	@p 02-033.bmp				\x{A}		1	:c/b01:!1	!	13	!6.!6			:b/b01:0						B:033_02_	R#012a	2	
B:034_02c	diagonal, trac	-bound up	@p 02-034c.bmp	@p 02-034c.bmp			Found through e_sect2.pdf as U+F824 ( ,) and U+2E88 (âºˆ,) but won't display	\x{A}		1	:c/b01:!1	!	11	!10			:b/b01:0				2E88		B:034_02c	R#018b	2	
B:035_02_	+drop, -drop	fission	ä¸·				Aufgrund folgender FÃ¤lle definiere ich einen neuen Baustein, der simp. mit "horns&" identisch ist.\x{A}éšŠé˜Ÿ (jap.: pinnacle, horns&sow)\x{A}æ›¾æ›¾ï€ å…Œå…‘\x{A}Ã¼ber "golden calf":\x{A}é€é€			1	:c/b01:!1	!	11	!10			:b/b01:0				4E37		B:035_02_		2	
B:035_03_	fission, one	horns	@p 03-035.bmp	@p 03-035.bmp			obsolete Heising explanation for form without the horizontal line: Variante von "horns", die erscheint, wenn darunter keine horizontale Linie steht\x{A}\x{A}Found through e_sect2.pdf as U+F7EA (??,) but won't display	\x{A}		1	:c/b01:!1	!	11	!10			:b/b01:0						B:035_03_		3	

--------------090302050909040309030109--
«58356» - Incorrect CSV generated if "quote_space => 0"
«61525» - eol not working for values other than "\n"?
«74216» - setting 'eol' affects global input record separator
1,2
3,4
5,6
7,8
«74330» - Text::CSV can be made to produce bad strings
«80680» - Text::CSV produces garbage on some data
«81295» - escaped sep_char discarded when only item in unquoted field
1,"\,",3
1,\,,3
«x1001» - Lines starting with "0" (Ruslan Dautkhanov)
"0","A"
"0","A"
"A","0"
"A","0"
«113279» - Failed parse + bind_columns causes memory corruption
foo "bar"
«115953» - Space stripped from middle of field value with allow_whitespace and allow_loose_quotes
"foo "bar" baz"
«120655» - bind_columns with strange behavior / length() from old value
«123320» - ext::CSV_XS bug w/Mac format files
