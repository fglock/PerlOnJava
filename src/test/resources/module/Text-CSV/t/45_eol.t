#!/usr/bin/perl

use strict;
$^W = 1;

use Test::More tests => 1182;

BEGIN {
    $ENV{PERL_TEXT_CSV} = $ENV{TEST_PERL_TEXT_CSV} || 0;
    require_ok "Text::CSV";
    plan skip_all => "Cannot load Text::CSV" if $@;
    require "./t/util.pl";
    }

$| = 1;

# Embedded newline tests

my $tfn = "_45eol.csv"; END { -f $tfn and unlink $tfn; }
my $def_rs = $/;

foreach my $rs ("\n", "\r\n", "\r") {
    for $\ (undef, $rs) {

	my $csv = Text::CSV->new ({ binary => 1 });
	   $csv->eol ($/ = $rs) unless defined $\;

	foreach my $pass (0, 1) {
	    my $fh;
	    if ($pass == 0) {
		open $fh, ">", $tfn or die "$tfn: $!\n";
		}
	    else {
		open $fh, "<", $tfn or die "$tfn: $!\n";
		}

	    foreach my $eol ("", "\r", "\n", "\r\n", "\n\r") {
		my $s_eol = join " - ", map { defined $_ ? $_ : "<undef>" } $\, $rs, $eol;
		   $s_eol =~ s/\r/\\r/g;
		   $s_eol =~ s/\n/\\n/g;

		my @p;
		my @f = ("", 1,
		    $eol, " $eol", "$eol ", " $eol ", "'$eol'",
		    "\"$eol\"", " \" $eol \"\n ", "EOL");

		if ($pass == 0) {
		    ok ($csv->combine (@f),		"combine |$s_eol|");
		    ok (my $str = $csv->string,		"string  |$s_eol|");
		    my $state = $csv->parse ($str);
		    ok ($state,				"parse   |$s_eol|");
		    if ($state) {
			ok (@p = $csv->fields,		"fields  |$s_eol|");
			}
		    else{
			is ($csv->error_input, $str,	"error   |$s_eol|");
			}

		    print $fh $str;
		    }
		else {
		    ok (my $row = $csv->getline ($fh),	"getline |$s_eol|");
		    is (ref $row, "ARRAY",		"row     |$s_eol|");
		    @p = @$row;
		    }

		local $, = "|";
		is_binary ("@p", "@f",			"result  |$s_eol|");
		}

	    close $fh;
	    }

	unlink $tfn;
	}
    }
$/ = $def_rs;

{   my $csv = Text::CSV->new ({ escape_char => undef });

    ok ($csv->parse (qq{"x"\r\n}), "Trailing \\r\\n with no escape char");

    is ($csv->eol ("\r"), "\r", "eol set to \\r");
    ok ($csv->parse (qq{"x"\r}),   "Trailing \\r with no escape char");

    ok ($csv->allow_whitespace (1), "Allow whitespace");
    ok ($csv->parse (qq{"x" \r}),  "Trailing \\r with no escape char");
    }

SKIP: {
    $] < 5.008 and skip "\$\\ tests don't work in perl 5.6.x and older", 2;
    {   local $\ = "#\r\n";
	my $csv = Text::CSV->new ();
	open my $fh, ">", $tfn or die "$tfn: $!\n";
	$csv->print ($fh, [ "a", 1 ]);
	close   $fh;
	open    $fh, "<", $tfn or die "$tfn: $!\n";
	local $/;
	is (<$fh>, "a,1#\r\n", "Strange \$\\");
	close   $fh;
	unlink  $tfn;
	}
    {   local $\ = "#\r\n";
	my $csv = Text::CSV->new ({ eol => $\ });
	open my $fh, ">", $tfn or die "$tfn: $!\n";
	$csv->print ($fh, [ "a", 1 ]);
	close   $fh;
	open    $fh, "<", $tfn or die "$tfn: $!\n";
	local $/;
	is (<$fh>, "a,1#\r\n", "Strange \$\\ + eol");
	close   $fh;
	unlink  $tfn;
	}
    }
$/ = $def_rs;

ok (1, "Auto-detecting \\r");
{   my @row = qw( a b c ); local $" = ",";
    for (["\n", "\\n"], ["\r\n", "\\r\\n"], ["\r", "\\r"]) {
	my ($eol, $s_eol) = @$_;
	open my $fh, ">", $tfn or die "$tfn: $!\n";
	print   $fh qq{@row$eol@row$eol@row$eol\x91};
	close   $fh;
	open    $fh, "<", $tfn or die "$tfn: $!\n";
	my $c = Text::CSV->new ({ binary => 1, auto_diag => 1 });
	is ($c->eol (),			"",		"default EOL");
	is_deeply ($c->getline ($fh),	[ @row ],	"EOL 1 $s_eol");
	is ($c->eol (),	$eol eq "\r" ? "\r" : "",	"EOL");
	is_deeply ($c->getline ($fh),	[ @row ],	"EOL 2 $s_eol");
	is_deeply ($c->getline ($fh),	[ @row ],	"EOL 3 $s_eol");
	close   $fh;
	unlink  $tfn;
	}
    }

ok (1, "Specific \\r test from tfrayner");
{   $/ = "\r";
    open my $fh, ">", $tfn or die "$tfn: $!\n";
    print   $fh qq{a,b,c$/}, qq{"d","e","f"$/};
    close   $fh;
    open    $fh, "<", $tfn or die "$tfn: $!\n";
    my $c = Text::CSV->new ({ eol => $/ });

    my $row;
    local $" = " ";
    ok ($row = $c->getline ($fh),	"getline 1");
    is (scalar @$row, 3,		"# fields");
    is ("@$row", "a b c",		"fields 1");
    ok ($row = $c->getline ($fh),	"getline 2");
    is (scalar @$row, 3,		"# fields");
    is ("@$row", "d e f",		"fields 2");
    close   $fh;
    unlink  $tfn;
    }
$/ = $def_rs;

ok (1, "EOL undef");
foreach my $se (0, 1) {
    $/ = "\r";
    ok (my $csv = Text::CSV->new ({
	eol        => undef,
	strict_eol => $se,
	}), "new csv with eol => undef");
    open my $fh, ">", $tfn or die "$tfn: $!\n";
    ok ($csv->print ($fh, [1, 2, 3]), "print");
    ok ($csv->print ($fh, [4, 5, 6]), "print");
    close   $fh;

    open    $fh, "<", $tfn or die "$tfn: $!\n";
    ok (my $row = $csv->getline ($fh),	"getline 1");
    is (scalar @$row, 5,		"# fields");
    is_deeply ($row, [ 1, 2, 34, 5, 6],	"fields 1");
    close   $fh;
    unlink  $tfn;
    }
$/ = $def_rs;

foreach my $eol ("!", "!!", "!\n", "!\n!", "!!!!!!!!", "!!!!!!!!!!",
		 "\n!!!!!\n!!!!!", "!!!!!\n!!!!!\n", "%^+_\n\0!X**",
		 "\r\n", "\r") {
    (my $s_eol = $eol) =~ s/\n/\\n/g;
    $s_eol =~ s/\r/\\r/g;
    $s_eol =~ s/\0/\\0/g;
    ok (1, "EOL $s_eol");
    ok (my $csv = Text::CSV->new ({ eol => $eol }), "new csv with eol => $s_eol");
    open my $fh, ">", $tfn or die "$tfn: $!\n";
    ok ($csv->print ($fh, [1, 2, 3]), "print");
    ok ($csv->print ($fh, [4, 5, 6]), "print");
    close $fh;

    foreach my $rs (undef, "", "\n", $eol, "!", "!\n", "\n!", "!\n!", "\n!\n") {
	local $/ = $rs;
	(my $s_rs = defined $rs ? $rs : "-- undef --") =~ s/\n/\\n/g;
	ok (1, "with RS $s_rs");
	open $fh, "<", $tfn or die "$tfn: $!\n";
	ok (my $row = $csv->getline ($fh),	"getline 1");
	is (scalar @$row, 3,			"field count");
	is_deeply ($row, [ 1, 2, 3],		"fields 1");
	ok (   $row = $csv->getline ($fh),	"getline 2");
	is (scalar @$row, 3,			"field count");
	is_deeply ($row, [ 4, 5, 6],		"fields 2");
	close $fh;
	}
    unlink $tfn;
    }
$/ = $def_rs;


foreach my $se (0, 1) {
    my @w;
    local $SIG{__WARN__} = sub { push @w => @_ };
    open my $fh, "<", "files/macosx.csv" or die "files/macosx.csv: $!";
    ok (1, "MacOSX exported file");
    ok (my $csv = Text::CSV->new ({
	auto_diag  => 1,
	binary     => 1,
	strict_eol => $se,
	}), "new csv");
    ok (my $row = $csv->getline ($fh),	"getline 1");
    is (scalar @$row, 15,		"field count");
    is ($row->[7], "",			"field 8");
    ok (   $row = $csv->getline ($fh),	"getline 2");
    is (scalar @$row, 15,		"field count");
    is ($row->[6], "Category",		"field 7");
    ok (   $row = $csv->getline ($fh),	"getline 3");
    is (scalar @$row, 15,		"field count");
    is ($row->[5], "Notes",		"field 6");
    ok (   $row = $csv->getline ($fh),	"getline 4");
    is (scalar @$row, 15,		"field count");
    is ($row->[7], "Points",		"field 8");
    ok (   $row = $csv->getline ($fh),	"getline 5");
    is (scalar @$row, 15,		"field count");
    is ($row->[7], 11,			"field 8");
    ok (   $row = $csv->getline ($fh),	"getline 6");
    is (scalar @$row, 15,		"field count");
    is ($row->[8], 34,			"field 9");
    ok (   $row = $csv->getline ($fh),	"getline 7");
    is (scalar @$row, 15,		"field count");
    is ($row->[7], 12,			"field 8");
    ok (   $row = $csv->getline ($fh),	"getline 8");
    is (scalar @$row, 15,		"field count");
    is ($row->[8], 2,			"field 9");
    ok (   $row = $csv->getline ($fh),	"getline 9");
    is (scalar @$row, 15,		"field count");
    is ($row->[3], "devs",		"field 4");
    ok (   $row = $csv->getline ($fh),	"getline 10");
    is (scalar @$row, 15,		"field count");
    is ($row->[3], "",			"field 4");
    ok (   $row = $csv->getline ($fh),	"getline 11");
    is (scalar @$row, 15,		"field count");
    is ($row->[6], "Mean",		"field 7");
    ok (   $row = $csv->getline ($fh),	"getline 12");
    is (scalar @$row, 15,		"field count");
    is ($row->[6], "Median",		"field 7");
    ok (   $row = $csv->getline ($fh),	"getline 13");
    is (scalar @$row, 15,		"field count");
    is ($row->[6], "Mode",		"field 7");
    ok (   $row = $csv->getline ($fh),	"getline 14");
    is (scalar @$row, 15,		"field count");
    is ($row->[6], "Min",		"field 7");
    ok (   $row = $csv->getline ($fh),	"getline 15");
    is (scalar @$row, 15,		"field count");
    is ($row->[6], "Max",		"field 7");
    ok (   $row = $csv->getline ($fh),	"getline 16");
    is (scalar @$row, 15,		"field count");
    is ($row->[0], "",			"field 1");
    close $fh;
    if ($se) {
	like ($w[0], qr{2016 - EOL}, "Got EOL warning");
	}
    else {
	is_deeply (\@w, [], "No warnings");
	}
    }

{   ok (my $csv = Text::CSV->new ({ auto_diag => 1, binary => 1 }), "new csv");
    ok ($csv->eol ("--"), "eol = --");
    ok ($csv->parse (qq{1,"2--3",4}),			"no eol");
    is_deeply ([$csv->fields], [ "1", "2--3", 4 ],	"parse");
    ok ($csv->parse (qq{1,"2--3",4--}),			"eol");
    is_deeply ([$csv->fields], [ "1", "2--3", 4 ],	"parse");
    ok ($csv->parse (qq{1,"2--3",4,--}),		",eol");
    is_deeply ([$csv->fields], [ "1", "2--3", 4, "" ],	"parse");

    open my $fh, ">", $tfn or die "$tfn: $!\n";
    print   $fh qq{1,"2--3",4--};
    print   $fh qq{1,"2--3",4,--};
    print   $fh qq{1,"2--3",4};
    close   $fh;
    open    $fh, "<", $tfn or die "$tfn: $!\n";
    is_deeply ($csv->getline ($fh), [ "1", "2--3", 4 ],		"getline eol");
    is_deeply ($csv->getline ($fh), [ "1", "2--3", 4, "" ],	"getline ,eol");
    is_deeply ($csv->getline ($fh), [ "1", "2--3", 4 ],		"getline eof");
    close   $fh;
    }

{   ok (my $csv = Text::CSV->new (), "new csv");
    ok ($csv->parse (qq{"a","b","c"\r\n}), "parse \\r\\n");
    is_deeply ([$csv->fields], [qw( a b c )], "result");
    ok ($csv->allow_loose_escapes (1), "allow loose escapes");
    ok ($csv->parse (qq{"a","b","c"\r\n}), "parse \\r\\n");
    is_deeply ([$csv->fields], [qw( a b c )], "result");
    }

foreach my $eol ("\n", "\r\n", "\r") {
    my $s_eol = $eol;
    $s_eol =~ s{\r}{\\r};
    $s_eol =~ s{\n}{\\n};
    foreach my $before ("1,2$eol", "") {
	open my $fh, ">", $tfn or die "$tfn: $!\n";
	print   $fh $before; # To test if skipping the very first line works
	print   $fh     $eol;	# skipped
	print   $fh qq{ $eol};	# -> [ " " ]
	print   $fh qq{,$eol};	# -> [ "", "" ]
	print   $fh     $eol;	# skipped
	print   $fh qq{""$eol};	# -> [ "" ]
	print   $fh qq{eol$eol};	# -> [ "eol" ]
	close   $fh;

	my @expect = ([ " " ], [ "", "" ], [ "" ], [ "eol" ]);
	$before and unshift @expect => [ 1, 2 ];

	open    $fh, "<", $tfn or die "$tfn: $!\n";
	my $csv = Text::CSV->new ({
	    skip_empty_rows => 1,
	    eol             => $eol,
	    });
	my @csv;
	while (my $row = $csv->getline ($fh)) {
	    push @csv => $row;
	    }
	close   $fh;
	is_deeply (\@csv, \@expect, "Empty lines skipped $s_eol\tEOL set");

	open    $fh, "<", $tfn or die "$tfn: $!\n";
	$csv = Text::CSV->new ({ skip_empty_rows => 1 });
	@csv = ();
	while (my $row = $csv->getline ($fh)) {
	    push @csv => $row;
	    }
	close   $fh;
	is_deeply (\@csv, \@expect, "Empty lines skipped $s_eol\tauto-detect");
	}
    }

my %ers = (
    # For backward compat :( - on 2024-12-05 XS and PP acted identical
    # some are not OK or at least do not DWIM in hindsight
    # strict : skip : reset : quoted
    '0:0:0:'  => [[ "Aardvark", "snort",   ],
                  [ "",                    ],
                  [ "Alpaca",   "spit",    ],
                  [ "Badger",   "growl",   ],
                  [ "Bat",      "screech", ],
                  [ "Bear",     "roar",    ],
                  [ "Bee",      "buzz",    ],
                  [ "",                    ],
                  [ "Camel",    "grunt",   ],
                  [ "",                    ],
                  [ "",                    ],
                  [ "Crow",     "caw",     ],
                  [ "",                    ],
                  [ "",                    ],
                  [ 2012,  0, 15,  0, "" ]], # EOF
    '0:0:1:'  => [[ "Aardvark", "snort",   ],
                  [ "",                    ],
                  [ "Alpaca",   "spit",    ],
                  [ "Badger",   "growl",   ],
                  [ "Bat",      "screech", ],
                  [ "Bear",     "roar",    ],
                  [ "Bee",      "buzz",    ],
                  [ "Camel",    "grunt",   ],
                  [ "Cobra",    "shh",     ],
                  [ "",                    ],
                  [ "Crow",     "caw",     ],
                  [ "Deer",     "bellow",  ],
                  [ "Dolphin",  "click",   ],
                  [ 2012,  0, 14,  0, "" ]], # EOF
    '0:1:0:'  => [[ "Aardvark", "snort",   ],
                  [ "Alpaca",   "spit",    ],
                  [ "Badger",   "growl",   ],
                  [ "Bat",      "screech", ],
                  [ "Bear",     "roar",    ],
                  [ "Bee",      "buzz",    ],
                  [ "Camel",    "grunt",   ],
                  [ "Cobra",    "shh",     ],
                  [ "Crow",     "caw",     ],
                  [ "Deer",     "bellow",  ],
                  [ 2012,  0, 11,  0, "" ]], # EOF
    '0:1:1:'  => [[ "Aardvark", "snort",   ],
                  [ "Alpaca",   "spit",    ],
                  [ "Badger",   "growl",   ],
                  [ "Bat",      "screech", ],
                  [ "Bear",     "roar",    ],
                  [ "Bee",      "buzz",    ],
                  [ "Camel",    "grunt",   ],
                  [ "Cobra",    "shh",     ],
                  [ "Crow",     "caw",     ],
                  [ "Deer",     "bellow",  ],
                  [ "Dolphin",  "click",   ],
                  [ 2012,  0, 12,  0, "" ]], # EOF
    '0:0:0:"' => [[ "Aardvark", "snort",   ],
                  [ "",                    ],
                  [ "Alpaca",   "spit",    ],
                  [ "Badger",   "growl",   ],
                  [ "Bat",      "screech", ],
                  [ "Bear",     "roar",    ],
                  [ "Bee",      "buzz",    ],
                  [ "",                    ],
                  [ "Camel",    "grunt",   ],
                  [ "",                    ],
                  [ "",                    ],
                  [ "Crow",     "caw",     ],
                  [ "",                    ],
                  [ "",                    ],
                  [ 2012,  0, 15,  0, "" ]], # EOF
    '0:0:1:"' => [[ "Aardvark", "snort",   ],
                  [ "",                    ],
                  [ "Alpaca",   "spit",    ],
                  [ "Badger",   "growl",   ],
                  [ "Bat",      "screech", ],
                  [ "Bear",     "roar",    ],
                  [ "Bee",      "buzz",    ],
                  [ "Camel",    "grunt",   ],
                  [ "Cobra",    "shh",     ],
                 #[ "Crow",     "caw",     ], WRONG
                  [ "Deer",     "bellow",  ],
                  [ "Dolphin",  "click",   ],
                  [ 2012,  0, 12,  0, "" ]], # EOF
    '0:1:0:"' => [[ "Aardvark", "snort",   ],
                  [ "Alpaca",   "spit",    ],
                  [ "Badger",   "growl",   ],
                  [ "Bat",      "screech", ],
                  [ "Bear",     "roar",    ],
                  [ "Bee",      "buzz",    ],
                  [ "Camel",    "grunt",   ],
                  [ "Cobra",    "shh",     ],
                  [ "Crow",     "caw",     ],
                  [ "Deer",     "bellow",  ],	# WRONG
                  [ 2012,  0, 11,  0, "" ]], # EOF
    '0:1:1:"' => [[ "Aardvark", "snort",   ],
                  [ "Alpaca",   "spit",    ],
                  [ "Badger",   "growl",   ],
                  [ "Bat",      "screech", ],
                  [ "Bear",     "roar",    ],
                  [ "Bee",      "buzz",    ],
                  [ "Camel",    "grunt",   ],
                  [ "Cobra",    "shh",     ],
                 #[ "Crow",     "caw",     ], WRONG
                  [ "Deer",     "bellow",  ],
                  [ "Dolphin",  "click",   ],
                  [ 2012,  0, 11,  0, "" ]], # EOF

    # Strict EOL warn / strict : skip : reset : quoted
    '1:0:0:'  => [[ "Aardvark", "snort",   ],
                  [ "",                    ],
                  [ "Alpaca",   "spit",    ],
                  [ "Badger",   "growl",   ],
                  [ "Bat",      "screech", ],
                  [ "Bear",     "roar",    ],
                  [ "Bee",      "buzz",    ],
                  [ "Camel",    "grunt",   ],
                  [ "Cobra",    "shh",     ],
                  [ "",                    ],
                  [ "Crow",     "caw",     ],
                  [ "Deer",     "bellow",  ],
                  [ "Dolphin",  "click",   ],
                  [ 2012, 13, 14,  2, "2016 - EOL" ]],
    '1:0:1:'  => [[ "Aardvark", "snort",   ],
                  [ "",                    ],
                  [ "Alpaca",   "spit",    ],
                  [ "Badger",   "growl",   ],
                  [ "Bat",      "screech", ],
                  [ "Bear",     "roar",    ],
                  [ "Bee",      "buzz",    ],
                  [ "Camel",    "grunt",   ],
                  [ "Cobra",    "shh",     ],
                  [ "",                    ],
                  [ "Crow",     "caw",     ],
                  [ "Deer",     "bellow",  ],
                  [ "Dolphin",  "click",   ],
                  [ 2012,  0, 14,  0, "" ]], # EOF
    '1:1:0:'  => [[ "Aardvark", "snort",   ],
                  [ "Alpaca",   "spit",    ],
                  [ "Badger",   "growl",   ],
                  [ "Bat",      "screech", ],
                  [ "Bear",     "roar",    ],
                  [ "Bee",      "buzz",    ],
                  [ "Camel",    "grunt",   ],
                  [ "Cobra",    "shh",     ],
                  [ "Crow",     "caw",     ],
                  [ "Deer",     "bellow",  ],
                  [ "Dolphin",  "click",   ],
                  [ 2012, 13, 12,  2, "2016 - EOL" ]],
    '1:1:1:'  => [[ "Aardvark", "snort",   ],
                  [ "Alpaca",   "spit",    ],
                  [ "Badger",   "growl",   ],
                  [ "Bat",      "screech", ],
                  [ "Bear",     "roar",    ],
                  [ "Bee",      "buzz",    ],
                  [ "Camel",    "grunt",   ],
                  [ "Cobra",    "shh",     ],
                  [ "Crow",     "caw",     ],
                  [ "Deer",     "bellow",  ],
                  [ "Dolphin",  "click",   ],
                  [ 2012, 10, 12,  1, "2016 - EOL" ]],
    '1:0:0:"' => [[ "Aardvark", "snort",   ],
                  [ "",                    ],
                  [ "Alpaca",   "spit",    ],
                  [ "Badger",   "growl",   ],
                  [ "Bat",      "screech", ],
                  [ "Bear",     "roar",    ],
                  [ "Bee",      "buzz",    ],
                  [ "Camel",    "grunt",   ],
                  [ "Cobra",    "shh",     ],
                  [ "",                    ],
		  [ "Crow",     "caw",     ],
                  [ "Deer",     "bellow",  ],
                  [ "Dolphin",  "click",   ],
                  [ 2012, 15, 14,  2, "2016 - EOL" ]],
    '1:0:1:"' => [[ "Aardvark", "snort",   ],
                  [ "",                    ],
                  [ "Alpaca",   "spit",    ],
                  [ "Badger",   "growl",   ],
                  [ "Bat",      "screech", ],
                  [ "Bear",     "roar",    ],
                  [ "Bee",      "buzz",    ],
                  [ "Camel",    "grunt",   ],
                  [ "Cobra",    "shh",     ],
		 #[ "Crow",     "caw",     ], WRONG: might change
                  [ "Deer",     "bellow",  ],
                  [ "Dolphin",  "click",   ],
                  [ 2012, 13, 12,  2, "2016 - EOL" ]],
    '1:1:0:"' => [[ "Aardvark", "snort",   ],
                  [ "Alpaca",   "spit",    ],
                  [ "Badger",   "growl",   ],
                  [ "Bat",      "screech", ],
                  [ "Bear",     "roar",    ],
                  [ "Bee",      "buzz",    ],
                  [ "Camel",    "grunt",   ],
                  [ "Cobra",    "shh",     ],
                  [ "Crow",     "caw",     ],
                  [ "Deer",     "bellow",  ],
                  [ "Dolphin",  "click",   ],
                  [ 2012, 15, 12,  2, "2016 - EOL" ]],
    '1:1:1:"' => [[ "Aardvark", "snort",   ],
                  [ "Alpaca",   "spit",    ],
                  [ "Badger",   "growl",   ],
                  [ "Bat",      "screech", ],
                  [ "Bear",     "roar",    ],
                  [ "Bee",      "buzz",    ],
                  [ "Camel",    "grunt",   ],
                  [ "Cobra",    "shh",     ],
                 #[ "Crow",     "caw",     ], WRONG, might change
                  [ "Deer",     "bellow",  ],
                  [ "Dolphin",  "click",   ],
                  [ 2012, 13, 11,  2, "2016 - EOL" ]],

    # Strict EOL croak / strict : skip : reset : quoted
    '2:0:0:'  => [[ "Aardvark", "snort",   ],
                  [ "",                    ],
                  [ "Alpaca",   "spit",    ],
                  [ 2016, 13,  4,  2, "2016 - EOL" ]],
    '2:0:1:'  => [[ "Aardvark", "snort",   ],
                  [ "",                    ],
                  [ "Alpaca",   "spit",    ],
                  [ "Badger",   "growl",   ],
                  [ "Bat",      "screech", ],
                  [ "Bear",     "roar",    ],
                  [ "Bee",      "buzz",    ],
                  [ "Camel",    "grunt",   ],
                  [ "Cobra",    "shh",     ],
                  [ "",                    ],
                  [ "Crow",     "caw",     ],
                  [ "Deer",     "bellow",  ],
                  [ "Dolphin",  "click",   ],
                  [ 2012,  0, 14,  0, "" ]], # EOF
    '2:1:0:'  => [[ "Aardvark", "snort",   ],
                  [ "Alpaca",   "spit",    ],
                  [ 2016, 13,  3,  2, "2016 - EOL" ]],
    '2:1:1:'  => [[ "Aardvark", "snort",   ],
                  [ "Alpaca",   "spit",    ],
                  [ "Badger",   "growl",   ],
                  [ "Bat",      "screech", ],
                  [ "Bear",     "roar",    ],
                  [ "Bee",      "buzz",    ],
                  [ "Camel",    "grunt",   ],
                  [ "Cobra",    "shh",     ],
                  [ 2016, 10,  9,  1, "2016 - EOL" ]],
    '2:0:0:"' => [[ "Aardvark", "snort",   ],
                  [ "",                    ],
                  [ "Alpaca",   "spit",    ],
                  [ 2016, 15,  4,  2, "2016 - EOL" ]],
    '2:0:1:"' => [[ "Aardvark", "snort",   ],
                  [ "",                    ],
                  [ "Alpaca",   "spit",    ],
                  [ "Badger",   "growl",   ],
                  [ "Bat",      "screech", ],
                  [ "Bear",     "roar",    ],
                  [ "Bee",      "buzz",    ],
                  [ "Camel",    "grunt",   ],
                  [ 2016, 13,  9,  2, "2016 - EOL" ]],
    '2:1:0:"' => [[ "Aardvark", "snort",   ],
                  [ "Alpaca",   "spit",    ],
                  [ 2016, 15,  3,  2, "2016 - EOL" ]],
    '2:1:1:"' => [[ "Aardvark", "snort",   ],
                  [ "Alpaca",   "spit",    ],
                  [ "Badger",   "growl",   ],
                  [ "Bat",      "screech", ],
                  [ "Bear",     "roar",    ],
                  [ "Bee",      "buzz",    ],
                  [ "Camel",    "grunt",   ],
                 #[ "Cobra",    "shh",     ], NOT stored, documented, might change
                  [ 2016, 13,  8,  2, "2016 - EOL" ]],
    );

foreach my $q ('', '"') {
    open my $fh, ">", $tfn or die "$tfn: $!\n";
    print   $fh "Aardvark,${q}snort${q}\r\n";
    print   $fh "\r\n"; # Empty line
    print   $fh "Alpaca,${q}spit${q}\r\n";
    print   $fh "Badger,${q}growl${q}\n"; # only newline
    print   $fh "Bat,${q}screech${q}\r\n";
    print   $fh "Bear,${q}roar${q}\r"; # only carriage return - no newline
    print   $fh "Bee,${q}buzz${q}\r\n";
    print   $fh "Camel,${q}grunt${q}\r\n";
    print   $fh "Cobra,${q}shh${q}\r\r"; # two CR's
    print   $fh "Crow,${q}caw${q}\r\n";
    print   $fh "Deer,${q}bellow${q}\n"; # only newline
    print   $fh "Dolphin,${q}click${q}\r\n";
    close   $fh;

    foreach my $se (0, 1, 2) {
	foreach my $ser (0, 1) {
	    foreach my $reset (0, 1) {
		my $tag = join ":" => $se, $ser, $reset, $q;
		open $fh, "<", $tfn or die "$tfn: $!\n";
		my $csv = Text::CSV->new ({
		    strict_eol      => $se,
		    skip_empty_rows => $ser,
		    auto_diag       => 1,
		    diag_verbose    => 1,
		    # Do NOT set binary!
		    });

		my (@r, @w);
		eval {
		    local $SIG{__WARN__} = sub { push @w => @_ };
		    while (my $row = $csv->getline ($fh)) {
			push @r => [ @$row ];
			$reset and $csv->eol (undef);
			}
		    close $fh;
		    };
		my @diag = $csv->error_diag;
		my $warn = join " | " => map { substr $_, 16, 10 } @w;
		my $got = [ @r, [ @diag[0, 2, 3, 4], $warn ]];
		my $exp = $ers{$tag};
		unless (is_deeply ($got, $exp, $tag)) {
		    # use Data::Peek;
		    #diag DDumper { got => $got, tag => $tag };
		    }
		}
	    }
	}
    }

# strict_eol should NOT warn/die/complain on deviating EOL inside quoted fields
{   open my $fh, ">", $tfn or die "$tfn: $!\n";
    print   $fh qq{Abrax,Booz,Wrox\r\n};
    print   $fh qq{Foo,"x\ry",Ornf\r\n};
    print   $fh qq{Cruy,"a\nb",Hye\r\n};
    print   $fh qq{Daj,"f\r\nb",Uf\r\n};
    close   $fh;

    foreach my $se (0, 1, 2) {
	my $tag = join ":" => "SE $se";
	open $fh, "<", $tfn or die "$tfn: $!\n";
	my $csv = Text::CSV->new ({
	    strict_eol   => $se,
	    auto_diag    => 1,
	    diag_verbose => 1,
	    binary       => 1,
	    });

	my (@r, @w);
	eval {
	    local $SIG{__WARN__} = sub { push @w => @_ };
	    while (my $row = $csv->getline ($fh)) {
		push @r => [ @$row ];
		}
	    close $fh;
	    };
	my @diag = $csv->error_diag;
	my $warn = join " | " => map { substr $_, 16, 10 } @w;
	is (scalar @r, 4, "$tag: Got 4 rows");
	is (scalar @w, 0, "$tag: Got no warnings");
	}
    }

1;
