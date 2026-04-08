#!/usr/bin/perl

use strict;
$^W = 1;	# use warnings;
$|  = 1;

use Config;
use Test::More;

BEGIN {
    unless (exists  $Config{useperlio} &&
	    defined $Config{useperlio} &&
	    $] >= 5.008                && # perlio was experimental in 5.6.2, but not reliable
	    $Config{useperlio} eq "define") {
	plan skip_all => "No reliable perlIO available";
	}
    else {
	plan tests => 136;
	}
    }

BEGIN {
    $ENV{PERL_TEXT_CSV} = $ENV{TEST_PERL_TEXT_CSV} || 0;
    use_ok "Text::CSV";
    plan skip_all => "Cannot load Text::CSV" if $@;
    require "./t/util.pl";
    }

$/ = "\n";
$\ = undef;

my $io;
my $io_str = "";
my $csv = Text::CSV->new ();

open  $io, ">", \$io_str or die "IO: $!";
ok (!$csv->print ($io, ["abc", "def\007", "ghi"]), "print bad character");
close $io;

for ( [  1, 1, 1, '""'				],
      [  2, 1, 1, '', ''			],
      [  3, 1, 0, '', 'I said, "Hi!"', ''	],
      [  4, 1, 0, '"', 'abc'			],
      [  5, 1, 0, 'abc', '"'			],
      [  6, 1, 1, 'abc', 'def', 'ghi'		],
      [  7, 1, 1, "abc\tdef", 'ghi'		],
      [  8, 1, 0, '"abc'			],
      [  9, 1, 0, 'ab"c'			],
      [ 10, 1, 0, '"ab"c"'			],
      [ 11, 0, 0, qq("abc\nc")			],
      [ 12, 1, 1, q(","), ','			],
      [ 13, 1, 0, qq("","I said,\t""Hi!""",""), '', qq(I said,\t"Hi!"), '' ],
      ) {
    my ($tst, $validp, $validg, @arg, $row) = @$_;

    open  $io, ">", \$io_str or die "IO: $!";
    is ($csv->print ($io, \@arg), $validp||"", "$tst - print ()");
    close $io;

    open  $io, ">", \$io_str or die "IO: $!";
    print $io join ",", @arg;
    close $io;

    open  $io, "<", \$io_str or die "IO: $!";
    $row = $csv->getline ($io);
    unless ($validg) {
	is ($row, undef, "$tst - false getline ()");
	next;
	}
    ok ($row, "$tst - good getline ()");
    $tst == 12 and @arg = (",", "", "");
    foreach my $a (0 .. $#arg) {
	(my $exp = $arg[$a]) =~ s/^"(.*)"$/$1/;
	is ($row->[$a], $exp, "$tst - field $a");
	}
    }

# This test because of a problem with DBD::CSV

ok (1, "Tests for DBD::CSV");
open  $io, ">", \$io_str or die "IO: $!";
$csv->binary (1);
$csv->eol    ("\r\n");
ok ($csv->print ($io, [ "id", "name"			]), "Bad character");
ok ($csv->print ($io, [   1,  "Alligator Descartes"	]), "Name 1");
ok ($csv->print ($io, [  "3", "Jochen Wiedmann"		]), "Name 2");
ok ($csv->print ($io, [   2,  "Tim Bunce"		]), "Name 3");
ok ($csv->print ($io, [ " 4", "Andreas König"		]), "Name 4");
ok ($csv->print ($io, [   5				]), "Name 5");
close $io;

my $expected = <<"CONTENTS";
id,name\015
1,"Alligator Descartes"\015
3,"Jochen Wiedmann"\015
2,"Tim Bunce"\015
" 4","Andreas König"\015
5\015
CONTENTS

open  $io, "<", \$io_str or die "IO: $!";
my $content = do { local $/; <$io> };
close $io;
is ($content, $expected, "Content");
open  $io, ">", \$io_str or die "IO: $!";
print $io $content;
close $io;
open  $io, "<", \$io_str or die "IO: $!";

my $fields;
print "# Retrieving data\n";
for (0 .. 5) {
    ok ($fields = $csv->getline ($io),			"Fetch field $_");
    is ($csv->eof, "",					"EOF");
    print "# Row $_: $fields (@$fields)\n";
    }
is ($csv->getline ($io), undef,				"Fetch field 6");
is ($csv->eof, 1,					"EOF");

{   ok (my $csv = Text::CSV->new ({ binary => 1, eol => "\n" }), "new csv");
    my ($out1, $out2, @fld, $fh) = ("", "", qw( 1 aa 3.14 ahhrg ));
    open $fh, ">", \$out1 or die "IO: $!";
    ok ($csv->print ($fh, \@fld), "Add line $_") for 1..3;
    close $fh;
    $csv->bind_columns (\(@fld));
    open $fh, ">", \$out2 or die "IO: $!";
    ok ($csv->print ($fh, \@fld), "Add line $_") for 1..3;
    close $fh;
    is ($out2, $out1, "ignoring bound columns");
    $out2 = "";
    open $fh, ">", \$out2 or die "IO: $!";
    ok ($csv->print ($fh, undef), "Add line $_") for 1..3;
    close $fh;
    is ($out2, $out1, "using bound columns");
    }

# Edge cases
for ([  1, 1,    0, "\n"		],
     [  2, 1,    0, "+\n"		],
     [  3, 1,    0, "+"			],
     [  4, 0, 2021, qq{"+"\n}		],
     [  5, 0, 2025, qq{"+\n}		],
     [  6, 0, 2011, qq{""+\n}		],
     [  7, 0, 2027, qq{"+"}		],
     [  8, 0, 2024, qq{"+}		],
     [  9, 0, 2011, qq{""+}		],
     [ 10, 1,    0, "\r"		],
     [ 11, 0, 2031, "\r\b"		],
     [ 12, 0, 2032, "+\r\b"		],
     [ 13, 0, 2032, "+\r\b+"		],
     [ 14, 0, 2022, qq{"\r"}		],
     [ 15, 0, 2022, qq{"\r\b" }		],
     [ 16, 0, 2022, qq{"\r\b"\t}	],
     [ 17, 0, 2025, qq{"+\r\b"}		],
     [ 18, 0, 2025, qq{"+\r\b+"}	],
     [ 19, 0, 2022, qq{"\r"\b}		],
     [ 20, 0, 2022, qq{"\r\b"\b}	],
     [ 21, 0, 2025, qq{"+\r\b"\b}	],
     [ 22, 0, 2025, qq{"+\r\b+"\b}	],
     [ 23, 0, 2037, qq{\b}		],
     [ 24, 0, 2026, qq{"\b"}		],
     ) {
    my ($tst, $valid, $err, $str) = @$_;
    $csv = Text::CSV->new ({ escape_char => "+" });
    $io_str = $str;
    open $io, "<", \$io_str or die "IO: $!"; binmode $io;
    my $row = $csv->getline ($io);
    close $io;
    my @err  = $csv->error_diag;
    my $sstr = _readable ($str);
    ok ($valid ? $row : !$row, "$tst - getline ESC +, '$sstr'");
    is ($err[0], $err, "Error expected $err");
    }

{   ok (my $csv = Text::CSV->new, "new for sep=");
    open my $fh, "<", \qq{sep=;\n"a b";3\n} or die "IO: $!";
    is_deeply ($csv->getline_all ($fh), [["a b", 3]], "valid sep=");
    is (($csv->error_diag)[0], 2012, "EOF");
    }

{   ok (my $csv = Text::CSV->new, "new for sep=");
    open my $fh, "<", \qq{sep=;\n"a b",3\n} or die "IO: $!";
    is_deeply (eval { $csv->getline_all ($fh); }, [], "invalid sep=");
    is (($csv->error_diag)[0], 2023, "error");
    }

{   ok (my $csv = Text::CSV->new, "new for sep=");
    open my $fh, "<", \qq{sep=XX\n"a b"XX3\n} or die "IO: $!";
    is_deeply (eval { $csv->getline_all ($fh); },
	[["a b", 3]], "multibyte sep=");
    is (($csv->error_diag)[0], 2012, "error");
    }

{   ok (my $csv = Text::CSV->new, "new for sep=");
    # To check that it is *only* supported on the first line
    open my $fh, "<", \qq{sep=;\n"a b";3\nsep=,\n"a b",3\n} or die "IO: $!";
    is_deeply ($csv->getline_all ($fh),
	[["a b","3"],["sep=,"]], "sep= not on 1st line");
    is (($csv->error_diag)[0], 2023, "error");
    }

{   ok (my $csv = Text::CSV->new, "new for sep=");
    my $sep = "#" x 80;
    open my $fh, "<", \qq{sep=$sep\n"a b",3\n2,3\n} or die "IO: $!";
    my $r = $csv->getline_all ($fh);
    is_deeply ($r, [["sep=$sep"],["a b","3"],[2,3]], "sep= too long");
    is (($csv->error_diag)[0], 2012, "EOF");
    }
