#!/usr/bin/perl

use strict;
$^W = 1;

use Test::More;

my $ebcdic = ord ("A") == 0xC1;
my $pu;
BEGIN {
    $pu = $ENV{PERL_UNICODE};
    $pu = defined $pu && ($pu eq "" || $pu =~ m/[oD]/ || ($pu =~ m/^[0-9]+$/ && $pu & 16));

    if ($] < 5.008002) {
        plan skip_all => "This test unit requires perl-5.8.2 or higher";
        }
    else {
	my $n = 1448;
	$pu and $n -= 120;
	plan tests => $n;
	}

    $ENV{PERL_TEXT_CSV} = $ENV{TEST_PERL_TEXT_CSV} || 0;

    use_ok "Text::CSV", "csv";
    # Encode up to and including 2.01 have an error in a regex:
    # False [] range "\s-" in regex; marked by <-- HERE in m/\bkoi8[\s- <-- HERE _]*([ru])$/
    # in Encode::Alias. This however does not influence this test, as then *all* encodings
    # are skipped as unsupported
    require Encode;
    require "./t/util.pl";
    }

$| = 1;

ok (my $csv = Text::CSV->new, "new for header tests");
is ($csv->sep_char, ",", "Sep = ,");

my $hdr_lc = [qw( bar foo )];

foreach my $sep (",", ";") {
    my $data = "bAr,foo\n1,2\n3,4,5\n";
    $data =~ s/,/$sep/g;

    $csv->column_names (undef);
    {   open my $fh, "<", \$data;
	ok (my $slf = $csv->header ($fh), "header");
	is ($slf, $csv, "Return self");
	is ($csv->sep_char, $sep, "Sep = $sep");
	is_deeply ([ $csv->column_names ], $hdr_lc, "headers");
	is_deeply ($csv->getline ($fh), [ 1, 2 ],    "Line 1");
	is_deeply ($csv->getline ($fh), [ 3, 4, 5 ], "Line 2");
	close $fh;
	}

    $csv->column_names (undef);
    {   open my $fh, "<", \$data;
	ok (my @hdr = $csv->header ($fh), "header");
	is_deeply (\@hdr, $hdr_lc, "Return headers");
	close $fh;
	}

    $csv->column_names (undef);
    {   open my $fh, "<", \$data;
	ok (my $slf = $csv->header ($fh), "header");
	is ($slf, $csv, "Return self");
	is ($csv->sep_char, $sep, "Sep = $sep");
	is_deeply ([ $csv->column_names ], $hdr_lc, "headers");
	is_deeply ($csv->getline_hr ($fh), { bar => 1, foo => 2 }, "Line 1");
	is_deeply ($csv->getline_hr ($fh), { bar => 3, foo => 4 }, "Line 2");
	close $fh;
	}

    {   open my $fh, "<", \$data;
	is_deeply (csv (in => $fh, bom => 1),
	    [{ bar => 1, foo => 2 }, { bar => 3, foo => 4 }],
	    "use header () from csv () with $sep");
	}

    {   open my $fh, "<", \$data;
	is_deeply (csv (in => $fh, seps => [ ",", ";" ]),
	    [{ bar => 1, foo => 2 }, { bar => 3, foo => 4 }],
	    "use header () from csv () with $sep");
	}

    {   open my $fh, "<", \$data;
	is_deeply (csv (in => $fh, bom => 1, key => "bar"),
	    { 1 => { bar => 1, foo => 2 }, 3 => { bar => 3, foo => 4 }},
	    "use header () from csv (key) with $sep");
	}

    {   open my $fh, "<", \$data;
	is_deeply (csv (in => $fh, munge => "uc", key => "BAR"),
	    { 1 => { BAR => 1, FOO => 2 }, 3 => { BAR => 3, FOO => 4 }},
	    "use header () from csv (key, uc) with $sep");
	}

    {   open my $fh, "<", \$data;
	is_deeply (csv (in => $fh, set_column_names => 0),
	    [[ "bar", "foo" ], [ 1, 2 ], [ 3, 4, 5 ]],
	    "use header () from csv () with $sep to ARRAY not setting column names");
	}
    {   open my $fh, "<", \$data;
	is_deeply (csv (in => $fh, set_column_names => 0, munge => "none"),
	    [[ "bAr", "foo" ], [ 1, 2 ], [ 3, 4, 5 ]],
	    "use header () from csv () with $sep to ARRAY not setting column names not lc");
	}
    }

my $sep_utf = byte_utf8a_to_utf8n ("\xe2\x81\xa3"); # U+2063 INVISIBLE SEPARATOR
my $sep_ok = [ "\t", "|", ",", ";", "##", $sep_utf ];
unless ($pu) {
    foreach my $sep (@$sep_ok) {
	my $data = "bAr,foo\n1,2\n3,4,5\n";
	$data =~ s/,/$sep/g;

	$csv->column_names (undef);
	{   open my $fh, "<", \$data;
	    ok (my $slf = $csv->header ($fh, $sep_ok), "header with specific sep set");
	    is ($slf, $csv, "Return self");
	    is (Encode::encode ("utf-8", $csv->sep), $sep, "Sep = $sep");
	    is_deeply ([ $csv->column_names ], $hdr_lc, "headers");
	    is_deeply ($csv->getline ($fh), [ 1, 2 ],    "Line 1");
	    is_deeply ($csv->getline ($fh), [ 3, 4, 5 ], "Line 2");
	    close $fh;
	    }

	$csv->column_names (undef);
	{   open my $fh, "<", \$data;
	    ok (my @hdr = $csv->header ($fh, $sep_ok), "header with specific sep set");
	    is_deeply (\@hdr, $hdr_lc, "Return headers");
	    close $fh;
	    }

	$csv->column_names (undef);
	{   open my $fh, "<", \$data;
	    ok (my $slf = $csv->header ($fh, { sep_set => $sep_ok }), "header with specific sep set as opt");
	    is ($slf, $csv, "Return self");
	    is (Encode::encode ("utf-8", $csv->sep), $sep, "Sep = $sep");
	    is_deeply ([ $csv->column_names ], $hdr_lc, "headers");
	    is_deeply ($csv->getline ($fh), [ 1, 2 ],    "Line 1");
	    is_deeply ($csv->getline ($fh), [ 3, 4, 5 ], "Line 2");
	    close $fh;
	    }

	$csv->column_names (undef);
	{   open my $fh, "<", \$data;
	    ok (my $slf = $csv->header ($fh, $sep_ok), "header with specific sep set");
	    is ($slf, $csv, "Return self");
	    is (Encode::encode ("utf-8", $csv->sep), $sep, "Sep = $sep");
	    is_deeply ([ $csv->column_names ], $hdr_lc, "headers");
	    is_deeply ($csv->getline_hr ($fh), { bar => 1, foo => 2 }, "Line 1");
	    is_deeply ($csv->getline_hr ($fh), { bar => 3, foo => 4 }, "Line 2");
	    close $fh;
	    }
	}
    }

for ( [ 1010, 0, qq{}		],	# Empty header
      [ 1011, 0, qq{a,b;c,d}	],	# Multiple allowed separators
      [ 1012, 0, qq{a,,b}	],	# Empty header field
      [ 1013, 0, qq{a,a,b}	],	# Non-unique headers
      [ 2027, 1, qq{a,"b\nc",c}	],	# Embedded newline binary on
      [ 2021, 0, qq{a,"b\nc",c}	],	# Embedded newline binary off
      ) {
    my ($err, $bin, $data) = @$_;
    $csv->binary ($bin);
    open my $fh, "<", \$data;
    my $self = eval { $csv->header ($fh); };
    is ($self, undef, "FAIL for '$data'");
    ok ($@, "Error");
    is (0 + $csv->error_diag, $err, "Error code $err");
    close $fh;
    }
{   open my $fh, "<", \"bar,bAr,bAR,BAR\n1,2,3,4";
    $csv->column_names (undef);
    ok ($csv->header ($fh, { munge_column_names => "none", detect_bom => 0 }), "non-unique unfolded headers");
    is_deeply ([ $csv->column_names ], [qw( bar bAr bAR BAR )], "Headers");
    close $fh;
    }
{   open my $fh, "<", \"bar,bAr,bAR,BAR\n1,2,3,4";
    $csv->column_names (undef);
    ok (my @hdr = $csv->header ($fh, { munge_column_names => "none" }), "non-unique unfolded headers");
    is_deeply (\@hdr, [qw( bar bAr bAR BAR )], "Headers from method");
    is_deeply ([ $csv->column_names ], [qw( bar bAr bAR BAR )], "Headers from column_names");
    close $fh;
    }

foreach my $sep (",", ";") {
    my $data = "bAr,foo\n1,2\n3,4,5\n";
    $data =~ s/,/$sep/g;

    $csv->column_names (undef);
    {   open my $fh, "<", \$data;
	ok (my $slf = $csv->header ($fh, { set_column_names => 0 }), "Header without column setting");
	is ($slf, $csv, "Return self");
	is ($csv->sep_char, $sep, "Sep = $sep");
	is_deeply ([ $csv->column_names ], [], "headers");
	is_deeply ($csv->getline ($fh), [ 1, 2 ],    "Line 1");
	is_deeply ($csv->getline ($fh), [ 3, 4, 5 ], "Line 2");
	close $fh;
	}
    $csv->column_names (undef);
    {   open my $fh, "<", \$data;
	ok (my @hdr = $csv->header ($fh, { set_column_names => 0 }), "Header without column setting");
	is_deeply (\@hdr, $hdr_lc, "Headers from method");
	is_deeply ([ $csv->column_names ], [], "Headers from column_names");
	close $fh;
	}
    }

foreach my $ss ("", "bad", sub { 1; }, \*STDOUT, +{}) {
    my $dta = "a,b\n1,2\n";
    open my $fh, "<", \$dta;
    my @hdr = eval { $csv->header ($fh, { sep_set => $ss }) };
    is (scalar @hdr, 0, "No header on invalid sep_set");
    is (0 + $csv->error_diag, 1500, "Error code");
    }

foreach my $dta ("", "\xfe\xff", "\xf7\x64\x4c", "\xdd\x73\x66\x73",
	"\x0e\xfe\xff", "\xfb\xee\x28", "\x84\x31\x95\x33") {
    open my $fh, "<", \$dta;
    my @hdr = eval { $csv->header ($fh) };
    is (scalar @hdr, 0, "No header on empty stream");
    is (0 + $csv->error_diag, 1010, "Error code");
    }

my $n;
for ([ undef, "_bar" ], [ "lc", "_bar" ], [ "uc", "_BAR" ], [ "none", "_bAr" ],
     [ sub { "column_".$n++ }, "column_0" ], [ "db", "bar" ]) {
    my ($munge, $hdr) = @$_;

    my $data = "_bAr,foo\n1,2\n3,4,5\n";
    my $how  = defined $munge ? ref $munge ? "CB" : $munge : "undef";

    $n = 0;
    $csv->column_names (undef);
    open my $fh, "<", \$data;
    ok (my $slf = $csv->header ($fh, { munge_column_names => $munge }), "munge header with $how");
    is (($csv->column_names)[0], $hdr, "folded header to $hdr");
    close $fh;

    $n = 0;
    $csv->column_names (undef);
    open $fh, "<", \$data;
    ok (my @hdr = $csv->header ($fh, { munge_column_names => $munge }), "munge header with $how");
    is ($hdr[0], $hdr, "folded header to $hdr");
    close $fh;
    }

my $fnm = "_85hdr.csv"; END { unlink $fnm; }

my $a_ring = chr (utf8::unicode_to_native (0xe5));
foreach my $irs ("\n", chr (utf8::unicode_to_native (0xaa))) {
    local $/ = $irs;
    foreach my $eol ("\n", "\r\n", "\r") {
	my $str = join $eol =>
	    qq{zoo,b${a_ring}r},
	    qq{1,"1 \x{20ac} each"},
	    "";
	for (   [ "none"       => ""			],
		[ "utf-8"      => "\xef\xbb\xbf"	],
		[ "utf-16be"   => "\xfe\xff"		],
		[ "utf-16le"   => "\xff\xfe"		],
		[ "utf-32be"   => "\x00\x00\xfe\xff"	],
		[ "utf-32le"   => "\xff\xfe\x00\x00"	],
		# Below 5 not (yet) supported by Encode
		[ "utf-1"      => "\xf7\x64\x4c"	],
		[ "utf-ebcdic" => "\xdd\x73\x66\x73"	],
		[ "scsu"       => "\x0e\xfe\xff"	],
		[ "bocu-1"     => "\xfb\xee\x28"	],
		[ "gb-18030"   => "\x84\x31\x95"	],
		#
		[ "UTF-8"      => "\x{feff}"		],
		) {
	    my ($enc, $bom) = @$_;
	    my ($enx, $box, $has_enc) = ($enc, $bom, 0);
	    $enc eq "UTF-8" || $enc eq "none" or
		$box = eval { Encode::encode ($enc, chr (0xfeff)) };
	    $enc eq "none" and $enx = "utf-8";

	    # On os390, Encode only supports the following EBCDIC
	    #  cp37, cp500, cp875, cp1026, cp1047, and posix-bc
	    # utf-ebcdic is not in the list
	    eval {
		no warnings "utf8";
		open my $fh, ">", $fnm;
		binmode $fh;
		if (defined $box) {
		    print $fh byte_utf8a_to_utf8n ($box);
		    print $fh Encode::encode ($enx, $str);
		    $has_enc = 1;
		    }
		else {
		    print $fh Encode::encode ("utf-8", $str);
		    }

		close $fh;
		};
	    #$ebcdic and $has_enc = 0; # TODO

	    $csv = Text::CSV->new ({ binary => 1, auto_diag => 9 });

	    SKIP: {
		$has_enc or skip "Encoding $enc not supported", $enc =~ m/^utf/ ? 10 : 9;
		$csv->column_names (undef);
		open my $fh, "<", $fnm;
		binmode $fh;
		ok (1, "$fnm opened for enc $enc");
		ok ($csv->header ($fh), "headers with BOM for $enc");
		$enc =~ m/^utf/ and is ($csv->{ENCODING}, uc $enc, "Encoding inquirable");

		is (($csv->column_names)[1], "b${a_ring}r", "column name was decoded");
		ok (my $row = $csv->getline_hr ($fh), "getline_hr");
		is ($row->{"b${a_ring}r"}, "1 \x{20ac} each", "Returned in Unicode");
		close $fh;

		my $aoh;
		ok ($aoh = csv (in => $fnm, bom => 1), "csv (bom => 1)");
		is_deeply ($aoh,
		    [{ zoo => 1, "b${a_ring}r" => "1 \x{20ac} each" }], "Returned data bom = 1");

		ok ($aoh = csv (in => $fnm, encoding => "auto"), "csv (encoding => auto)");
		is_deeply ($aoh,
		    [{ zoo => 1, "b${a_ring}r" => "1 \x{20ac} each" }], "Returned data auto");
		}

	    SKIP: {
		$has_enc or skip "Encoding $enc not supported", 7;
		$csv->column_names (undef);
		open my $fh, "<", $fnm;
		$enc eq "none" or binmode $fh, ":encoding($enc)";
		ok (1, "$fnm opened for enc $enc");
		ok ($csv->header ($fh), "headers with BOM for $enc");
		is (($csv->column_names)[1], "b${a_ring}r", "column name was decoded");
		ok (my $row = $csv->getline_hr ($fh), "getline_hr");
		is ($row->{"b${a_ring}r"}, "1 \x{20ac} each", "Returned in Unicode");
		close $fh;

		ok (my $aoh = csv (in => $fnm, bom => 1), "csv (bom => 1)");
		is_deeply ($aoh,
		    [{ zoo => 1, "b${a_ring}r" => "1 \x{20ac} each" }], "Returned data");
		}

	    unlink $fnm;
	    }
	}
    }

{   # Header after first line with sep=
    open my $fh, ">", $fnm or die "$fnm: $!";
    print $fh "sep=;\n";
    print $fh "a;b 1;c\n";
    print $fh "1;2;3\n";
    close $fh;
    ok (my $aoh = csv (in => $fnm, munge => "db"), "Read header with sep=;");
    is_deeply ($aoh, [{ a => 1, "b_1" => 2, c => 3 }], "Munged to db with sep");
    }
