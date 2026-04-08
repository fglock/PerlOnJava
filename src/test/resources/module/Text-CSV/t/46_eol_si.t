#!/usr/bin/perl

use strict;
$^W = 1;

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
	plan tests => 562;
	}
    }

BEGIN {
    $ENV{PERL_TEXT_CSV} = $ENV{TEST_PERL_TEXT_CSV} || 0;
    require_ok "Text::CSV";
    plan skip_all => "Cannot load Text::CSV" if $@;
    require "./t/util.pl";
    }

$| = 1;

# Embedded newline tests

my $file = "";

my $def_rs = $/;

foreach my $rs ("\n", "\r\n", "\r") {
    for $\ (undef, $rs) {

	my $csv = Text::CSV->new ({ binary => 1 });
	   $csv->eol ($/ = $rs) unless defined $\;

	my $fh;
	foreach my $pass (0, 1) {
	    if ($pass == 0) {
		$file = "";
		open $fh, ">", \$file or die "IO: $!\n";
		}
	    else {
		open $fh, "<", \$file or die "IO: $!\n";
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
	$file = "";
	open my $fh, ">", \$file or die "IO: $!\n";
	$csv->print ($fh, [ "a", 1 ]);
	close   $fh;
	open    $fh, "<", \$file or die "IO: $!\n";
	local $/;
	is (<$fh>, "a,1#\r\n", "Strange \$\\");
	close   $fh;
	}
    {   local $\ = "#\r\n";
	my $csv = Text::CSV->new ({ eol => $\ });
	$file = "";
	open my $fh, ">", \$file or die "IO: $!\n";
	$csv->print ($fh, [ "a", 1 ]);
	close   $fh;
	open    $fh, "<", \$file or die "IO: $!\n";
	local $/;
	is (<$fh>, "a,1#\r\n", "Strange \$\\ + eol");
	close   $fh;
	}
    }
$/ = $def_rs;

ok (1, "Auto-detecting \\r");
{   my @row = qw( a b c ); local $" = ",";
    for (["\n", "\\n"], ["\r\n", "\\r\\n"], ["\r", "\\r"]) {
	my ($eol, $s_eol) = @$_;
	$file = "";
	open my $fh, ">", \$file or die "IO: $!\n";
	print   $fh qq{@row$eol@row$eol@row$eol\x91};
	close   $fh;
	open    $fh, "<", \$file or die "IO: $!\n";
	my $c = Text::CSV->new ({ binary => 1, auto_diag => 1 });
	is ($c->eol (),			"",		"default EOL");
	is_deeply ($c->getline ($fh),	[ @row ],	"EOL 1 $s_eol");
	is ($c->eol (),	$eol eq "\r" ? "\r" : "",	"EOL");
	is_deeply ($c->getline ($fh),	[ @row ],	"EOL 2 $s_eol");
	is_deeply ($c->getline ($fh),	[ @row ],	"EOL 3 $s_eol");
	close $fh;
	}
    }

ok (1, "Specific \\r test from tfrayner");
{   $/ = "\r";
    $file = "";
    open my $fh, ">", \$file or die "IO: $!\n";
    print   $fh qq{a,b,c$/}, qq{"d","e","f"$/};
    close   $fh;
    open    $fh, "<", \$file or die "IO: $!\n";
    my $c = Text::CSV->new ({ eol => $/ });

    my $row;
    local $" = " ";
    ok ($row = $c->getline ($fh),	"getline 1");
    is (scalar @$row, 3,		"# fields");
    is ("@$row", "a b c",		"fields 1");
    ok ($row = $c->getline ($fh),	"getline 2");
    is (scalar @$row, 3,		"# fields");
    is ("@$row", "d e f",		"fields 2");
    close $fh;
    }
$/ = $def_rs;

ok (1, "EOL undef");
{   $/ = "\r";
    ok (my $csv = Text::CSV->new ({ eol => undef }), "new csv with eol => undef");
    $file = "";
    open my $fh, ">", \$file or die "IO: $!\n";
    ok ($csv->print ($fh, [1, 2, 3]), "print");
    ok ($csv->print ($fh, [4, 5, 6]), "print");
    close $fh;

    open $fh, "<", \$file or die "IO: $!\n";
    ok (my $row = $csv->getline ($fh),	"getline 1");
    is (scalar @$row, 5,		"# fields");
    is_deeply ($row, [ 1, 2, 34, 5, 6],	"fields 1");
    close $fh;
    }
$/ = $def_rs;

foreach my $eol ("!", "!!", "!\n", "!\n!") {
    (my $s_eol = $eol) =~ s/\n/\\n/g;
    ok (1, "EOL $s_eol");
    ok (my $csv = Text::CSV->new ({ eol => $eol }), "new csv with eol => $s_eol");
    $file = "";
    open my $fh, ">", \$file or die "IO: $!\n";
    ok ($csv->print ($fh, [1, 2, 3]), "print");
    ok ($csv->print ($fh, [4, 5, 6]), "print");
    close $fh;

    foreach my $rs (undef, "", "\n", $eol, "!", "!\n", "\n!", "!\n!", "\n!\n") {
	local $/ = $rs;
	(my $s_rs = defined $rs ? $rs : "-- undef --") =~ s/\n/\\n/g;
	ok (1, "with RS $s_rs");
	open $fh, "<", \$file or die "IO: $!\n";
	ok (my $row = $csv->getline ($fh),	"getline 1");
	is (scalar @$row, 3,			"# fields");
	is_deeply ($row, [ 1, 2, 3],		"fields 1");
	ok (   $row = $csv->getline ($fh),	"getline 2");
	is (scalar @$row, 3,			"# fields");
	is_deeply ($row, [ 4, 5, 6],		"fields 2");
	close $fh;
	}
    }
$/ = $def_rs;

{   ok (my $csv = Text::CSV->new, "new for say");
    my $foo;
    open my $fh, ">", \$foo or die "IO: $!\n";
    ok ($csv->say ($fh, [ 1, 2 ]),	"say");
    close $fh;
    is ($foo, "1,2$/", "content with eol \$/");
    $foo = "";
    $csv->eol (undef);
    open $fh, ">", \$foo or die "IO: $!\n";
    ok ($csv->say ($fh, [ 1, 2 ]),	"say");
    close $fh;
    $foo = "";
    $csv->eol ("");
    open $fh, ">", \$foo or die "IO: $!\n";
    ok ($csv->say ($fh, [ 1, 2 ]),	"say");
    close $fh;
    is ($foo, "1,2$/", "content with eol \$/");
    $foo = "";
    $csv->eol ("#");
    open $fh, ">", \$foo or die "IO: $!\n";
    ok ($csv->say ($fh, [ 1, 2 ]),	"say");
    close $fh;
    is ($foo, "1,2#", "content with eol #");
    $foo = "";
    $csv->eol ("0");
    open $fh, ">", \$foo or die "IO: $!\n";
    ok ($csv->say ($fh, [ 1, 2 ]),	"say");
    close $fh;
    is ($foo, "1,20", "content with eol 0");
    }

{   ok (my $csv = Text::CSV->new, "new for say");
    my $foo;
    my $dta = "x";
    ok ($csv->bind_columns (\$dta), "bind columns");

    local $\ = undef;
    local $/ = "\n";

    open my $fh, ">", \$foo or die "IO: $!\n";
    ok ($csv->print ($fh, undef), "print");
    close $fh;
    is ($foo, "x", "print, no newline");

    $foo = "";
    open $fh, ">", \$foo or die "IO: $!\n";
    ok ($csv->say ($fh, undef), "say");
    close $fh;
    is ($foo, "x\n", "say, with newline");
    }

1;
