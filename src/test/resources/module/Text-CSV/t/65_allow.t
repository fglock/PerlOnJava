#!/usr/bin/perl

use strict;
$^W = 1;

#use Test::More "no_plan";
 use Test::More tests => 1119;

BEGIN {
    $ENV{PERL_TEXT_CSV} = $ENV{TEST_PERL_TEXT_CSV} || 0;
    use_ok "Text::CSV", ();
    plan skip_all => "Cannot load Text::CSV" if $@;
    require "./t/util.pl";
    }

my $tfn = "_65test.csv"; END { -f $tfn and unlink $tfn; }
my $csv;

ok (1, "Allow unescaped quotes");
# Allow unescaped quotes inside an unquoted field
{   my @bad = (
	# valid, line
	[ 1, 1,    0, qq{foo,bar,"baz",quux}				],
	[ 2, 0, 2034, qq{rj,bs,r"jb"s,rjbs}				],
	[ 3, 0, 2034, qq{some "spaced" quote data,2,3,4}		],
	[ 4, 1,    0, qq{and an,entirely,quoted,"field"}		],
	[ 5, 1,    0, qq{and then,"one with ""quoted"" quotes",okay,?}	],
	);

    for (@bad) {
	my ($tst, $valid, $err, $bad) = @$_;
	$csv = Text::CSV->new ();
	ok ($csv,			"$tst - new (alq => 0)");
	is ($csv->parse ($bad), $valid,	"$tst - parse () fail");
	is (0 + $csv->error_diag, $err,	"$tst - error $err");

	$csv->allow_loose_quotes (1);
	ok ($csv->parse ($bad),		"$tst - parse () pass");
	ok (my @f = $csv->fields,	"$tst - fields");
	}

    #$csv = Text::CSV->new ({ quote_char => '"', escape_char => "=" });
    #ok (!$csv->parse (qq{foo,d'uh"bar}),	"should fail");
    }

ok (1, "Allow loose quotes inside quoted");
# Allow unescaped quotes inside a quoted field
{   my @bad = (
	# valid, line
	[ 1, 1,    0, qq{foo,bar,"baz",quux}				],
	[ 2, 0, 2023, qq{rj,bs,"r"jb"s",rjbs}				],
	[ 3, 0, 2023, qq{"some "spaced" quote data",2,3,4}		],
	[ 4, 1,    0, qq{and an,entirely,quoted,"field"}		],
	[ 5, 1,    0, qq{and then,"one with ""quoted"" quotes",okay,?}	],
	);

    for (@bad) {
	my ($tst, $valid, $err, $bad) = @$_;
	$csv = Text::CSV->new ();
	ok ($csv,			"$tst - new (alq => 0)");
	is ($csv->parse ($bad), $valid,	"$tst - parse () fail");
	is (0 + $csv->error_diag, $err,		"$tst - error $err");

	$csv->allow_loose_quotes (1);
	is ($csv->parse ($bad), $valid,	"$tst - parse () fail with lq");
	is (0 + $csv->error_diag, $err,		"$tst - error $err");

	$csv->escape_char (undef);
	ok ($csv->parse ($bad),		"$tst - parse () pass");
	ok (my @f = $csv->fields,	"$tst - fields");
	}
    }

ok (1, "Allow loose escapes");
# Allow escapes to escape characters that should not be escaped
{   my @bad = (
	# valid, line
	[ 1, 1,    0, qq{1,foo,bar,"baz",quux}				],
	[ 2, 1,    0, qq{2,escaped,"quote\\"s",in,"here"}		],
	[ 3, 1,    0, qq{3,escaped,quote\\"s,in,"here"}			],
	[ 4, 1,    0, qq{4,escap\\'d chars,allowed,in,unquoted,fields}	],
	[ 5, 0, 2025, qq{5,42,"and it\\'s dog",}			],

	[ 6, 1,    0, qq{\\,}						],
	[ 7, 1,    0, qq{\\}						],
	[ 8, 0, 2035, qq{foo\\}						],
	);

    for (@bad) {
	my ($tst, $valid, $err, $bad) = @$_;
	$csv = Text::CSV->new ({ escape_char => "\\" });
	ok ($csv,			"$tst - new (ale => 0)");
	is ($csv->parse ($bad), $valid,	"$tst - parse () fail");
	is (0 + $csv->error_diag, $err,		"$tst - error $err");

	$csv->allow_loose_escapes (1);
	if ($tst >= 8) {
	    # Should always fail
	    ok (!$csv->parse ($bad),	"$tst - parse () fail");
	    is (0 + $csv->error_diag, $err,		"$tst - error $err");
	    }
	else {
	    ok ($csv->parse ($bad),	"$tst - parse () pass");
	    ok (my @f = $csv->fields,	"$tst - fields");
	    }
	}
    }

ok (1, "Allow whitespace");
# Allow whitespace to surround sep char
{   my @bad = (
	# valid, line
	[  1, 1,    0, qq{1,foo,bar,baz,quux}				],
	[  2, 1,    0, qq{1,foo,bar,"baz",quux}				],
	[  3, 1,    0, qq{1, foo,bar,"baz",quux}			],
	[  4, 1,    0, qq{ 1,foo,bar,"baz",quux}			],
	[  5, 0, 2034, qq{1,foo,bar, "baz",quux}			],
	[  6, 1,    0, qq{1,foo ,bar,"baz",quux}			],
	[  7, 1,    0, qq{1,foo,bar,"baz",quux }			],
	[  8, 1,    0, qq{1,foo,bar,"baz","quux"}			],
	[  9, 0, 2023, qq{1,foo,bar,"baz" ,quux}			],
	[ 10, 0, 2023, qq{1,foo,bar,"baz","quux" }			],
	[ 11, 0, 2034, qq{ 1 , foo , bar , "baz" , quux }		],
	[ 12, 0, 2034, qq{  1  ,  foo  ,  bar  ,  "baz"  ,  quux  }	],
	[ 13, 0, 2034, qq{  1  ,  foo  ,  bar  ,  "baz"\t ,  quux  }	],
	);

    foreach my $eol ("", "\n", "\r", "\r\n") {
	my $s_eol = _readable ($eol);
	for (@bad) {
	    my ($tst, $ok, $err, $bad) = @$_;
	    $csv = Text::CSV->new ({ eol => $eol, binary => 1 });
	    ok ($csv,				"$s_eol / $tst - new - '$bad')");
	    is ($csv->parse ($bad), $ok,	"$s_eol / $tst - parse () fail");
	    is (0 + $csv->error_diag, $err,			"$tst - error $err");

	    $csv->allow_whitespace (1);
	    ok ($csv->parse ("$bad$eol"),	"$s_eol / $tst - parse () pass");

	    ok (my @f = $csv->fields,		"$s_eol / $tst - fields");

	    local $" = ",";
	    is ("@f", $bad[0][-1],		"$s_eol / $tst - content");
	    }
	}
    }

ok (1, "Allow whitespace");
# Allow whitespace to surround sep char
{   my @bad = (
	# test, ok, line
	[  1, 1,    0, qq{1,foo,bar,baz,quux}				],
	[  2, 1,    0, qq{1,foo,bar,"baz",quux}				],
	[  3, 1,    0, qq{1, foo,bar,"baz",quux}			],
	[  4, 1,    0, qq{ 1,foo,bar,"baz",quux}			],
	[  5, 0, 2034, qq{1,foo,bar, "baz",quux}			],
	[  6, 1,    0, qq{1,foo ,bar,"baz",quux}			],
	[  7, 1,    0, qq{1,foo,bar,"baz",quux }			],
	[  8, 1,    0, qq{1,foo,bar,"baz","quux"}			],
	[  9, 0, 2023, qq{1,foo,bar,"baz" ,quux}			],
	[ 10, 0, 2023, qq{1,foo,bar,"baz","quux" }			],
	[ 11, 0, 2023, qq{1,foo,bar,"baz","quux" }			],
	[ 12, 0, 2034, qq{ 1 , foo , bar , "baz" , quux }		],
	[ 13, 0, 2034, qq{  1  ,  foo  ,  bar  ,  "baz"  ,  quux  }	],
	[ 14, 0, 2034, qq{  1  ,  foo  ,  bar  ,  "baz"\t ,  quux  }	],
	);

    foreach my $eol ("", "\n", "\r", "\r\n") {
	my $s_eol = _readable ($eol);
	for (@bad) {
	    my ($tst, $ok, $err, $bad) = @$_;
	    $csv = Text::CSV->new ({
		eol		 => $eol,
		binary		 => 1,
		});
	    ok ($csv,				"$s_eol / $tst - new - '$bad')");
	    is ($csv->parse ($bad), $ok,	"$s_eol / $tst - parse () fail");
	    is (0 + $csv->error_diag, $err,			"$tst - error $err");

	    $csv->allow_whitespace (1);
	    ok ($csv->parse ("$bad$eol"),	"$s_eol / $tst - parse () pass");

	    ok (my @f = $csv->fields,		"$s_eol / $tst - fields");

	    local $" = ",";
	    is ("@f", $bad[0][-1],		"$s_eol / $tst - content");
	    }
	}
    }

ok (1, "blank_is_undef");
foreach my $conf (
	[ 0, 0, 0,	1, "",    " ", '""', 2, "",    "",    ""	],
	[ 0, 0, 1,	1, undef, " ", '""', 2, undef, undef, undef	],
	[ 0, 1, 0,	1, "",    " ", '""', 2, "",    "",    ""	],
	[ 0, 1, 1,	1, undef, " ", '""', 2, undef, undef, undef	],
	[ 1, 0, 0,	1, "",    " ", '""', 2, "",    "",    ""	],
	[ 1, 0, 1,	1, "",    " ", '""', 2, undef, "",    undef	],
	[ 1, 1, 0,	1, "",    " ", '""', 2, "",    "",    ""	],
	[ 1, 1, 1,	1, "",    " ", '""', 2, undef, "",    undef	],
	) {
    my ($aq, $aw, $bu, @expect, $str) = @$conf;
    $csv = Text::CSV->new ({ always_quote => $aq, allow_whitespace => $aw, blank_is_undef => $bu });
    ok ($csv,	"new ({ aq $aq aw $aw bu $bu })");
    ok ($csv->combine (1, "", " ", '""', 2, undef, "", undef), "combine ()");
    ok ($str = $csv->string,			"string ()");
    foreach my $eol ("", "\n", "\r\n") {
	my $s_eol = _readable ($eol);
	ok ($csv->parse ($str.$eol),	"parse (*$str$s_eol*)");
	ok (my @f = $csv->fields,	"fields ()");
	is_deeply (\@f, \@expect,	"result");
	}
    }

ok (1, "empty_is_undef");
foreach my $conf (
	[ 0, 0, 0,	1, "",    " ", '""', 2, "",    "",    ""	],
	[ 0, 0, 1,	1, undef, " ", '""', 2, undef, undef, undef	],
	[ 0, 1, 0,	1, "",    " ", '""', 2, "",    "",    ""	],
	[ 0, 1, 1,	1, undef, " ", '""', 2, undef, undef, undef	],
	[ 1, 0, 0,	1, "",    " ", '""', 2, "",    "",    ""	],
	[ 1, 0, 1,	1, undef, " ", '""', 2, undef, undef, undef	],
	[ 1, 1, 0,	1, "",    " ", '""', 2, "",    "",    ""	],
	[ 1, 1, 1,	1, undef, " ", '""', 2, undef, undef, undef	],
	) {
    my ($aq, $aw, $bu, @expect, $str) = @$conf;
    $csv = Text::CSV->new ({ always_quote => $aq, allow_whitespace => $aw, empty_is_undef => $bu });
    ok ($csv,	"new ({ aq $aq aw $aw bu $bu })");
    ok ($csv->combine (1, "", " ", '""', 2, undef, "", undef), "combine ()");
    ok ($str = $csv->string,			"string ()");
    foreach my $eol ("", "\n", "\r\n") {
	my $s_eol = _readable ($eol);
	ok ($csv->parse ($str.$eol),	"parse (*$str$s_eol*)");
	ok (my @f = $csv->fields,	"fields ()");
	is_deeply (\@f, \@expect,	"result");
	}
    }


ok (1, "Trailing junk");
foreach my $bin (0, 1) {
    foreach my $eol (undef, "\r") {
	my $s_eol = _readable ($eol);
	my $csv = Text::CSV->new ({ binary => $bin, eol => $eol });
	ok ($csv, "$s_eol - new ()");
	my @bad = (
	    # test, line
	    [ 1, qq{"\r\r\n"\r}		],
	    [ 2, qq{"\r\r\n"\r\r}	],
	    [ 3, qq{"\r\r\n"\r\r\n}	],
	    [ 4, qq{"\r\r\n"\t \r}	],
	    [ 5, qq{"\r\r\n"\t \r\r}	],
	    [ 6, qq{"\r\r\n"\t \r\r\n}	],
	    );
	my @pass = (    0,    0,    0, 1 );
	my @fail = ( 2022, 2022, 2023, 0 );

	foreach my $arg (@bad) {
	    my ($tst, $bad) = @$arg;
	    my $ok = ($bin << 1) | ($eol ? 1 : 0);
	    is ($csv->parse ($bad), $pass[$ok],	"$tst $ok - parse () default");
	    is (0 + $csv->error_diag, $fail[$ok],		"$tst $ok - error $fail[$ok]");

	    $csv->allow_whitespace (1);
	    is ($csv->parse ($bad), $pass[$ok],	"$tst $ok - parse () allow");
	    is (0 + $csv->error_diag, $fail[$ok],		"$tst $ok - error $fail[$ok]");
	    }
	}
    }

{   ok (1, "verbatim");
    my $csv = Text::CSV->new ({
	sep_char => "^",
	binary   => 1,
	});

    my @str = (
	qq{M^^Abe^Timmerman#\r\n},
	qq{M^^Abe\nTimmerman#\r\n},
	);

    my $gc;

    ok (1, "verbatim on parse ()");
    foreach $gc (0, 1) {
	$csv->verbatim ($gc);

	ok ($csv->parse ($str[0]),		"\\n   $gc parse");
	my @fld = $csv->fields;
	is (@fld, 4,				"\\n   $gc fields");
	is ($fld[2], "Abe",			"\\n   $gc fld 2");
	if ($gc) {	# Note line ending is still there!
	    is ($fld[3], "Timmerman#\r\n",	"\\n   $gc fld 3");
	    }
	else {		# Note the stripped \r!
	    is ($fld[3], "Timmerman#",		"\\n   $gc fld 3");
	    }

	ok ($csv->parse ($str[1]),		"\\n   $gc parse");
	@fld = $csv->fields;
	is (@fld, 3,				"\\n   $gc fields");
	if ($gc) {	# All newlines verbatim
	    is ($fld[2], "Abe\nTimmerman#\r\n",	"\\n   $gc fld 2");
	    }
	else {		# Note, rest is next line
	    is ($fld[2], "Abe",			"\\n   $gc fld 2");
	    }
	}

    $csv->eol ($/ = "#\r\n");
    foreach $gc (0, 1) {
	$csv->verbatim ($gc);

	ok ($csv->parse ($str[0]),		"#\\r\\n $gc parse");
	my @fld = $csv->fields;
	is (@fld, 4,				"#\\r\\n $gc fields");
	is ($fld[2], "Abe",			"#\\r\\n $gc fld 2");
	is ($fld[3], $gc ? "Timmerman#\r\n"
			 : "Timmerman",		"#\\r\\n $gc fld 3");

	ok ($csv->parse ($str[1]),		"#\\r\\n $gc parse");
	@fld = $csv->fields;
	is (@fld, 3,				"#\\r\\n $gc fields");
	is ($fld[2], $gc ? "Abe\nTimmerman#\r\n"
			 : "Abe",		"#\\r\\n $gc fld 2");
	}

    my $fh;
    ok (1, "verbatim on getline (\$fh)");
    open  $fh, ">", $tfn or die "$tfn: $!\n";
    print $fh @str, "M^Abe^*\r\n";
    close $fh;

    foreach $gc (0, 1) {
	$csv->verbatim ($gc);

	open $fh, "<", $tfn or die "$tfn: $!\n";

	my $row;
	ok ($row = $csv->getline ($fh),		"#\\r\\n $gc getline");
	is (@$row, 4,				"#\\r\\n $gc fields");
	is ($row->[2], "Abe",			"#\\r\\n $gc fld 2");
	is ($row->[3], "Timmerman",		"#\\r\\n $gc fld 3");

	ok ($row = $csv->getline ($fh),		"#\\r\\n $gc parse");
	is (@$row, 3,				"#\\r\\n $gc fields");
	is ($row->[2], $gc ? "Abe\nTimmerman"
			   : "Abe",		"#\\r\\n $gc fld 2");
	}

    $gc = $csv->verbatim ();
    ok (my $row = $csv->getline ($fh),		"#\\r\\n $gc parse EOF");
    is (@$row, 3,				"#\\r\\n $gc fields");
    is ($row->[2], "*\r\n",			"#\\r\\n $gc fld 2");

    close $fh;

    $csv = Text::CSV->new ({
	binary		=> 0,
	verbatim	=> 1,
	eol		=> "#\r\n",
	});
    open    $fh, ">", $tfn or die "$tfn: $!\n";
    print   $fh $str[1];
    close   $fh;
    open    $fh, "<", $tfn or die "$tfn: $!\n";
    is ($csv->getline ($fh), undef,	"#\\r\\n $gc getline 2030");
    is (0 + $csv->error_diag, 2030,	"Got 2030");
    close  $fh;
    unlink $tfn;
    }

{   ok (1, "keep_meta_info on getline ()");

    my $csv = Text::CSV->new ({ eol => "\n" });

    open my $fh, ">", $tfn or die "$tfn: $!\n";
    print   $fh qq{1,"",,"Q",2\n};
    close   $fh;

    is ($csv->keep_meta_info (0), 0,		"No meta info");
    open    $fh, "<", $tfn or die "$tfn: $!\n";
    my $row = $csv->getline ($fh);
    ok ($row,					"Get 1st line");
    $csv->error_diag ();
    is ($csv->is_quoted (2), undef,		"Is field 2 quoted?");
    is ($csv->is_quoted (3), undef,		"Is field 3 quoted?");
    close $fh;

    open    $fh, ">", $tfn or die "$tfn: $!\n";
    print   $fh qq{1,"",,"Q",2\n};
    close   $fh;

    is ($csv->keep_meta_info (1), 1,		"Keep meta info");
    open    $fh, "<", $tfn or die "$tfn: $!\n";
    $row = $csv->getline ($fh);
    ok ($row,					"Get 2nd line");
    $csv->error_diag ();
    is ($csv->is_quoted (2), 0,			"Is field 2 quoted?");
    is ($csv->is_quoted (3), 1,			"Is field 3 quoted?");
    close  $fh;
    unlink $tfn;
    }

{   my $csv = Text::CSV->new ({});

    my $s2023 = qq{2023,",2008-04-05,"  \tFoo, Bar",\n}; # "
    #                                ^

    is ( $csv->parse ($s2023), 0,		"Parse 2023");
    is (($csv->error_diag)[0], 2023,		"Fail code 2023");
    is (($csv->error_diag)[2], 19,		"Fail position");

    is ( $csv->allow_whitespace (1), 1,		"Allow whitespace");
    is ( $csv->parse ($s2023), 0,		"Parse 2023");
    is (($csv->error_diag)[0], 2023,		"Fail code 2023");
    is (($csv->error_diag)[2], 22,		"Space is eaten now");
    }

{   my $csv = Text::CSV->new ({ allow_unquoted_escape => 1, escape_char => "=" });
    my $str = q{1,3,=};
    is ( $csv->parse ($str),   0,		"Parse trailing ESC");
    is (($csv->error_diag)[0], 2035,		"Fail code 2035");

    $str .= "0";
    is ( $csv->parse ($str),   1,		"Parse trailing ESC");
    is_deeply ([ $csv->fields ], [ 1,3,"\0" ],	"Parse passed");
    }
