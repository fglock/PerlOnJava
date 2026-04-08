#!/usr/bin/perl

use strict;
$^W = 1;

use Test::More;
use charnames ":full";

BEGIN {
    if ($] < 5.008001) {
	plan skip_all => "UTF8 tests useless in this ancient perl version";
	}
    else {
	plan tests => 93;
	}
    }

BEGIN {
    $ENV{PERL_TEXT_CSV} = $ENV{TEST_PERL_TEXT_CSV} || 0;
    require_ok "Text::CSV";
    plan skip_all => "Cannot load Text::CSV" if $@;
    require "./t/util.pl";
    }

my $tfn = "_50test.csv"; END { -f $tfn and unlink $tfn; }
# No binary => 1, as UTF8 is supposed to be allowed without it
my $csv = Text::CSV->new ({
    always_quote   => 1,
    keep_meta_info => 1,
    });

# Special characters to check:
# 0A = \n  2C = ,  20 =     22 = "  
# 0D = \r  3B = ;
foreach my $test (
  # Space-like characters
  [ "\x{0000A0}", "U+0000A0 NO-BREAK SPACE"				],
  [ "\x{00200B}", "U+00200B ZERO WIDTH SPACE"				],
  # Some characters with possible problems in the code point
  [ "\x{000122}", "U+000122 LATIN CAPITAL LETTER G WITH CEDILLA"	],
  [ "\x{002C22}", "U+002C22 GLAGOLITIC CAPITAL LETTER SPIDERY HA"	],
  [ "\x{000A2C}", "U+000A2C GURMUKHI LETTER BA"				],
  [ "\x{000E2C}", "U+000E2C THAI CHARACTER LO CHULA"			],
  [ "\x{010A2C}", "U+010A2C KHAROSHTHI LETTER VA"			],
  # Characters with possible problems in the encoded representation
  #  Should not be possible. ASCII is coded in 000..127, all other
  #  characters in 128..255
  ) {
    my ($u, $msg) = @$test;
    ($u = "$u\x{0123}") =~ s/.$//;	# Make sure it's marked UTF8
    my @in  = ("", " ", $u, "");
    my $exp = join ",", map { qq{"$_"} } @in;

    ok ($csv->combine (@in),		"combine $msg");

    my $str = $csv->string;
    is_binary ($str, $exp,		"string  $msg");

    ok ($csv->parse ($str),		"parse   $msg");
    my @out = $csv->fields;
    # Cannot use is_deeply (), because of the binary content
    is (scalar @in, scalar @out,	"fields  $msg");
    is_binary ($in[$_], $out[$_],	"field $_ $msg") for 0 .. $#in;
    }

# Test if the UTF8 part is accepted, but the \n is not
is ($csv->parse (qq{"\x{0123}\n\x{20ac}"}), 0, "\\n still needs binary");
is ($csv->binary, 0, "bin flag still unset");
is ($csv->error_diag + 0, 2021, "Error 2021");

open my $fh, ">:encoding(utf-8)", $tfn or die "$tfn: $!\n";
print   $fh qq{"\N{LATIN CAPITAL LETTER O WITH STROKE}l/Vin",0\n};
close   $fh;
SKIP: {
    open my $fh, "<:encoding(utf-8)", $tfn or
	skip "Cannot open UTF-8 test file", 6;

    my $row;
    ok ($row = $csv->getline ($fh), "read/parse");

    is ($csv->is_quoted (0),	1,	"First  field is quoted");
    is ($csv->is_quoted (1),	0,	"Second field is not quoted");
    is ($csv->is_binary (0),	1,	"First  field is binary");
    is ($csv->is_binary (1),	0,	"Second field is not binary");

    ok (utf8::valid ($row->[0]),	"First field is valid utf8");

    $csv->combine (@$row);
    ok (utf8::valid ($csv->string),	"Combined string is valid utf8");
    }

# Test quote_binary
$csv->always_quote (0);
$csv->quote_space  (0);
$csv->quote_binary (0);
ok ($csv->combine (" ", 1, "\x{20ac} "),	"Combine");
is ($csv->string, qq{ ,1,\x{20ac} },		"String 0-0");
$csv->quote_binary (1);
ok ($csv->combine (" ", 1, "\x{20ac} "),	"Combine");
is ($csv->string, qq{ ,1,"\x{20ac} "},		"String 0-1");

$csv->quote_space  (1);
$csv->quote_binary (0);
ok ($csv->combine (" ", 1, "\x{20ac} "),	"Combine");
is ($csv->string, qq{" ",1,"\x{20ac} "},	"String 1-0");
ok ($csv->quote_binary (1),			"quote binary on");
ok ($csv->combine (" ", 1, "\x{20ac} "),	"Combine");
is ($csv->string, qq{" ",1,"\x{20ac} "},	"String 1-1");

ok ($csv->parse (qq{,1,"f\x{014d}o, 3""56",,bar,\r\n}), "example from XS");
is_deeply ([$csv->fields], [
    "", 1, qq{f\x{014d}o, 3"56}, "", "bar", "" ], "content");

open  $fh, ">:encoding(utf-8)", $tfn or die "$tfn: $!\n";
print $fh "euro\n\x{20ac}\neuro\n";
close $fh;
open  $fh, "<:encoding(utf-8)", $tfn or die "$tfn: $!\n";

SKIP: {
    my $out = "";
    my $isutf8 = $] < 5.008001 ?
	sub { !$_[0]; } :	# utf8::is_utf8 () not available in 5.8.0
	sub { utf8::is_utf8 ($out); };
    ok ($csv->auto_diag (1),			"auto diag");
    ok ($csv->binary (1),   			"set binary");
    ok ($csv->bind_columns (\$out),		"bind");
    ok ($csv->getline ($fh),			"parse");
    is ($csv->is_binary (0),	0,		"not binary");
    is ($out,			"euro",		"euro");
    ok (!$isutf8->(1),				"not utf8");
    ok ($csv->getline ($fh),			"parse");
    is ($csv->is_binary (0),	1,		"is binary");
    is ($out,			"\x{20ac}",	"euro");
    ok ($isutf8->(0),				"is utf8");
    ok ($csv->getline ($fh),			"parse");
    is ($csv->is_binary (0),	0,		"not binary");
    is ($out,			"euro",		"euro");
    ok (!$isutf8->(1),				"not utf8");
    close $fh;
    }
