#!/usr/bin/perl

use strict;
$^W = 1;

use Test::More tests => 24;

BEGIN {
    $ENV{PERL_TEXT_CSV} = $ENV{TEST_PERL_TEXT_CSV} || 0;
    require_ok "Text::CSV";
    plan skip_all => "Cannot load Text::CSV" if $@;
    require "./t/util.pl";
    }

$| = 1;

my @binField = ("abc\0def\n\rghi", "ab\"ce,\031\"'", "\266");

my $csv = Text::CSV->new ({ binary => 1 });
ok ($csv->combine (@binField),					"combine ()");

my $string;
is_binary ($string = $csv->string,
	   qq("abc"0def\n\rghi","ab""ce,\031""'",\266),		"string ()");

ok ($csv->parse ($string),					"parse ()");
is ($csv->fields, scalar @binField,				"field count");

my @field = $csv->fields ();
for (0 .. $#binField) {
    is ($field[$_], $binField[$_],				"Field $_");
    }

ok (1,								"eol \\r\\n");
$csv->eol ("\r\n");
ok ($csv->combine (@binField),					"combine ()");
is_binary ($csv->string,
	   qq("abc"0def\n\rghi","ab""ce,\031""'",\266\r\n),	"string ()");

ok (1,								"eol \\n");
$csv->eol ("\n");
ok ($csv->combine (@binField),					"combine ()");
is_binary ($csv->string,
	   qq("abc"0def\n\rghi","ab""ce,\031""'",\266\n),	"string ()");

ok (1,								"eol ,xxxxxxx\\n");
$csv->eol (",xxxxxxx\n");
ok ($csv->combine (@binField),					"combine ()");
is_binary ($csv->string,
	   qq("abc"0def\n\rghi","ab""ce,\031""'",\266,xxxxxxx\n),	"string ()");

$csv->eol ("\n");
ok (1,								"quote_char undef");
$csv->quote_char (undef);
ok ($csv->combine ("abc","def","ghi"),				"combine");
is ($csv->string, "abc,def,ghi\n",				"string ()");

# Ken's test
ok (1,								"always_quote");
my $csv2 = Text::CSV->new ({ always_quote => 1 });
ok ($csv2,							"new ()");
ok ($csv2->combine ("abc","def","ghi"),				"combine ()");
is ($csv2->string, '"abc","def","ghi"',				"string ()");
