#!/usr/bin/perl

use strict;
$^W = 1;
use charnames ":full";

use Test::More;
$| = 1;

BEGIN {
    $] < 5.008002 and
	plan skip_all => "UTF8 tests useless in this ancient perl version";
    }

my @tests;
my $ebcdic = ord ("A") == 0xC1;

BEGIN {
    delete $ENV{PERLIO};

    my $pu = $ENV{PERL_UNICODE};
    $pu = defined $pu && ($pu eq "" || $pu =~ m/[oD]/ || ($pu =~ m/^[0-9]+$/ && $pu & 16));

    my $euro_ch = "\x{20ac}";

    utf8::encode    (my $bytes = $euro_ch);
    utf8::downgrade (my $bytes_dn = $bytes);
    utf8::upgrade   (my $bytes_up = $bytes);

    @tests = (
	# $test                        $perlio             $data,      $encoding $expect_w
	# ---------------------------- ------------------- ----------- --------- ----------
	[ "Unicode  default",          "",                 $euro_ch,   "utf8",   $pu ? "no warn" : "warn" ],
	[ "Unicode  binmode",          "[binmode]",        $euro_ch,   "utf8",   "warn",    ],
	[ "Unicode  :utf8",            ":utf8",            $euro_ch,   "utf8",   "no warn", ],
	[ "Unicode  :encoding(utf8)",  ":encoding(utf8)",  $euro_ch,   "utf8",   "no warn", ],
	[ "Unicode  :encoding(UTF-8)", ":encoding(UTF-8)", $euro_ch,   "utf8",   "no warn", ],

	[ "bytes dn default",          "",                 $bytes_dn,  "[none]", "no warn", ],
	[ "bytes dn binmode",          "[binmode]",        $bytes_dn,  "[none]", "no warn", ],
	[ "bytes dn :utf8",            ":utf8",            $bytes_dn,  "utf8",   "no warn", ],
	[ "bytes dn :encoding(utf8)",  ":encoding(utf8)",  $bytes_dn,  "utf8",   "no warn", ],
	[ "bytes dn :encoding(UTF-8)", ":encoding(UTF-8)", $bytes_dn,  "utf8",   "no warn", ],

	[ "bytes up default",          "",                 $bytes_up,  "[none]", "no warn", ],
	[ "bytes up binmode",          "[binmode]",        $bytes_up,  "[none]", "no warn", ],
	[ "bytes up :utf8",            ":utf8",            $bytes_up,  "utf8",   "no warn", ],
	[ "bytes up :encoding(utf8)",  ":encoding(utf8)",  $bytes_up,  "utf8",   "no warn", ],
	[ "bytes up :encoding(UTF-8)", ":encoding(UTF-8)", $bytes_up,  "utf8",   "no warn", ],
	);

    my $builder = Test::More->builder;
    binmode $builder->output,         ":encoding(utf8)";
    binmode $builder->failure_output, ":encoding(utf8)";
    binmode $builder->todo_output,    ":encoding(utf8)";

    plan tests => 11 + 6 * @tests + 4 * 22 + 6 + 10 + 2;
    }

BEGIN {
    $ENV{PERL_TEXT_CSV} = $ENV{TEST_PERL_TEXT_CSV} || 0;
    use_ok "Text::CSV", ("csv");
    plan skip_all => "Cannot load Text::CSV" if $@;
    require "./t/util.pl";
    }

sub hexify { join " ", map { sprintf "%02x", $_ } unpack "C*", @_ }
sub warned { length ($_[0]) ? "warn" : "no warn" }

my $csv = Text::CSV->new ({ binary => 1, auto_diag => 1 });

for (@tests) {
    my ($test, $perlio, $data, $enc, $expect_w) = @$_;

    my $expect = qq{"$data"};
    $enc eq "utf8" and utf8::encode ($expect);

    my ($p_out, $p_fh) = ("");
    my ($c_out, $c_fh) = ("");

    if ($perlio eq "[binmode]") {
	open $p_fh, ">",        \$p_out or die "IO: $!\n"; binmode $p_fh;
	open $c_fh, ">",        \$c_out or die "IO: $!\n"; binmode $c_fh;
	}
    else {
	open $p_fh, ">$perlio", \$p_out or die "IO: $!\n";
	open $c_fh, ">$perlio", \$c_out or die "IO: $!\n";
	}

    my $p_warn = "";
    {	local $SIG{__WARN__} = sub { $p_warn .= join "", @_ };
	ok ((print $p_fh qq{"$data"}),        "$test perl print");
	close $p_fh;
	}

    my $c_warn = "";
    {	local $SIG{__WARN__} = sub { $c_warn .= join "", @_ };
	ok ($csv->print ($c_fh, [ $data ]),   "$test csv print");
	close $c_fh;
	}

    is (hexify ($c_out), hexify ($p_out),   "$test against Perl");
    is (hexify ($c_out), hexify ($expect),  "$test against expected");

    is (warned ($c_warn), warned ($p_warn), "$test against Perl warning");
    is (warned ($c_warn), $expect_w,        "$test against expected warning");
    }

# Test automatic upgrades for valid UTF-8
{   my $blob = pack "C*", 0..255; $blob =~ tr/",//d;
    # perl-5.10.x has buggy SvCUR () on blob
    $] >= 5.010000 && $] <= 5.012001 and $blob =~ tr/\0//d;
    my $b1 = "\x{b6}";		# PILCROW SIGN in ISO-8859-1
    my $b2 = $ebcdic		# ARABIC COMMA in UTF-8
	? "\x{b8}\x{57}\x{53}"
	: "\x{d8}\x{8c}";
    my @data = (
	qq[1,aap,3],		# No diac
	qq[1,a${b1}p,3],	# Single-byte
	qq[1,a${b2}p,3],	# Multi-byte
	qq[1,"$blob",3],	# Binary shit
	) x 2;
    my $data = join "\n" => @data;
    my @expect = ("aap", "a\266p", "a\x{060c}p", $blob) x 2;

    my $csv = Text::CSV->new ({ binary => 1, auto_diag => 1 });

    foreach my $bc (undef, 3) {
	my @read;

	# Using getline ()
	open my $fh, "<", \$data or die "IO: $!\n"; binmode $fh;
	$bc and $csv->bind_columns (\my ($f1, $f2, $f3));
	is (scalar $csv->bind_columns, $bc, "Columns_bound?");
	while (my $row = $csv->getline ($fh)) {
	    push @read, $bc ? $f2 : $row->[1];
	    }
	close $fh;
	is_deeply (\@read, \@expect, "Set and reset UTF-8 ".($bc?"no bind":"bind_columns"));
	is_deeply ([ map { utf8::is_utf8 ($_) } @read ],
	    [ "", "", 1, "", "", "", 1, "" ], "UTF8 flags");

	# Using parse ()
	@read = map {
	    $csv->parse ($_);
	    $bc ? $f2 : ($csv->fields)[1];
	    } @data;
	is_deeply (\@read, \@expect, "Set and reset UTF-8 ".($bc?"no bind":"bind_columns"));
	is_deeply ([ map { utf8::is_utf8 ($_) } @read ],
	    [ "", "", 1, "", "", "", 1, "" ], "UTF8 flags");
	}
    }

my $sep = "\x{2665}";#"\N{INVISIBLE SEPARATOR}";
my $quo = "\x{2661}";#"\N{FULLWIDTH QUOTATION MARK}";
foreach my $new (0, 1, 2, 3) {
    my %attr = (
	binary       => 1,
	always_quote => 1,
	);;
    $new & 1 and $attr{sep}   = $sep;
    $new & 2 and $attr{quote} = $quo;
    my $csv = Text::CSV->new (\%attr);

    my $s = $attr{sep}   || ',';
    my $q = $attr{quote} || '"';

    note ("Test SEP: '$s', QUO: '$q'") if $Test::More::VERSION > 0.81;
    is ($csv->sep,   $s, "sep");
    is ($csv->quote, $q, "quote");

    foreach my $data (
	    [ 1,		2		],
	    [ "\N{EURO SIGN}",	"\N{SNOWMAN}"	],
#	    [ $sep,		$quo		],
	    ) {

	my $exp8 = join $s => map { qq{$q$_$q} } @$data;
	utf8::encode (my $expb = $exp8);
	my @exp = ($expb, $exp8);

	ok ($csv->combine (@$data),		"combine");
	my $x = $csv->string;
	is ($csv->string, $exp8,		"string");

	open my $fh, ">:encoding(utf8)", \(my $out = "") or die "IO: $!\n";
	ok ($csv->print ($fh, $data),		"print with UTF8 sep");
	close $fh;

	is ($out, $expb,			"output");

	ok ($csv->parse ($expb),		"parse");
	is_deeply ([ $csv->fields ],    $data,	"fields");

	open $fh, "<", \$expb or die "IO: $!\n"; binmode $fh;
	is_deeply ($csv->getline ($fh), $data,	"data from getline ()");
	close $fh;

	$expb =~ tr/"//d;

	ok ($csv->parse ($expb),		"parse");
	is_deeply ([ $csv->fields ],    $data,	"fields");

	open $fh, "<", \$expb or die "IO: $!\n"; binmode $fh;
	is_deeply ($csv->getline ($fh), $data,	"data from getline ()");
	close $fh;
	}
    }

{   my $h = "\N{WHITE HEART SUIT}";
    my $H = "\N{BLACK HEART SUIT}";
    my $str = "${h}I$h$H${h}L\"${h}ve$h$H${h}Perl$h";
    utf8::encode ($str);
    ok (my $aoa = csv (in => \$str, sep => $H, quote => $h),	"Hearts");
    is_deeply ($aoa, [[ "I", "L${h}ve", "Perl"]],		"I $H Perl");

    ok (my $csv = Text::CSV->new ({
			binary => 1, sep => $H, quote => $h }),	"new hearts");
    ok ($csv->combine (@{$aoa->[0]}),				"combine");
    ok ($str = $csv->string,					"string");
    utf8::decode ($str);
    is ($str, "I${H}${h}L\"${h}ve${h}${H}Perl", "Correct quotation");
    }

# Tests pulled from tests in Raku
{   my $csv = Text::CSV->new ({ binary => 1, auto_diag => 1 });
    my $h = pack "C*", 224, 34, 204, 182;
    ok ($csv->combine (1, $h, 3));
    ok (my $s = $csv->string, "String");
    my $b = $h;
    utf8::encode ($b);
    ok ($csv->combine (1, $b, 3));
    ok ($s = $csv->string, "String");
    }

{   my $h = qq{\x{10fffd}xE0"}; #"
    my $b = $h;
    ok ($csv->combine (1, $b, 3));
    ok (my $s = $csv->string, "String");
    $b = $h;
    utf8::encode ($b);
    ok ($csv->combine (1, $b, 3));
    ok ($s = $csv->string, "String");
    $b = $h;
    utf8::encode ($b);
    ok ($csv->combine (1, $b, 3));
    ok ($s = $csv->string, "String");
    }

{   my $file = "Eric,\N{LATIN CAPITAL LETTER E WITH ACUTE}RIC\n";
    utf8::encode ($file);
    open my $fh, "<", \$file or die $!;

    my $csv = Text::CSV->new ({ binary => 1, auto_diag => 2 });
    is_deeply (
	[ $csv->header ($fh) ],
	[ "eric", "\N{LATIN SMALL LETTER E WITH ACUTE}ric" ],
	"Lowercase unicode header");
    }

{   my $file = "Eric,\N{LATIN SMALL LETTER E WITH ACUTE}ric\n";
    utf8::encode ($file);
    open my $fh, "<", \$file or die $!;

    my $csv = Text::CSV->new ({ binary => 1, auto_diag => 2 });
    is_deeply (
	[ $csv->header ($fh, { munge => "uc" }) ],
	[ "ERIC", "\N{LATIN CAPITAL LETTER E WITH ACUTE}RIC" ],
	"Uppercase unicode header");
    }
