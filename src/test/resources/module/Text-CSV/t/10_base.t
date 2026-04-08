#!/usr/bin/perl

use strict;
$^W = 1;	# use warnings core since 5.6

use Test::More tests => 64;

BEGIN {
    $ENV{PERL_TEXT_CSV} = $ENV{TEST_PERL_TEXT_CSV} || 0;
    use_ok "Text::CSV";
    plan skip_all => "Cannot load Text::CSV" if $@;
    }

# empty subclass test
#
package Empty_Subclass;

@Empty_Subclass::ISA = qw( Text::CSV );

package main;

ok (new Text::CSV,		"Indirect object notation");

# Important: Do not modify these tests unless you have a good
# reason. This file ought to guarantee compatibility to Text::CSV.
#
my $empty = Empty_Subclass->new ();
is (ref $empty, "Empty_Subclass",			"Empty Subclass");
is ($empty->version (), Text::CSV->version (),	"Version");
ok ($empty->parse (""),					"Subclass parse ()");
ok ($empty->combine (""),				"Subclass combine ()");

ok ($empty->new,					"new () based on object");

my $csv;
ok ($csv = Text::CSV->new,				"new ()");
is ($csv->fields, undef,				"fields () before parse ()");
is ($csv->string, undef,				"string () undef before combine");

# Important: Do not modify these tests unless you have a good
# reason. This file ought to guarantee compatibility to Text::CSV.
#
ok (1,							"combine () & string () tests");
ok (!$csv->combine (),					"Missing arguments");
ok (!$csv->combine ("abc", "def\n", "ghi"),		"Bad character");
is ( $csv->error_input, "def\n",			"Error_input ()");
ok ( $csv->combine (""),				"Empty string - combine ()");
is ( $csv->string, '',					"Empty string - string ()");
ok ( $csv->combine ("", " "),				"Two fields, one space - combine ()");
is ( $csv->string, '," "',				"Two fields, one space - string ()");
ok ( $csv->combine ("", 'I said, "Hi!"', ""),		"Hi! - combine ()");
is ( $csv->string, ',"I said, ""Hi!""",',		"Hi! - string ()");
ok ( $csv->combine ('"', "abc"),			"abc - combine ()");
is ( $csv->string, '"""",abc',				"abc - string ()");
ok ( $csv->combine (","),				"comma - combine ()");
is ( $csv->string, '","',				"comma - string ()");
ok ( $csv->combine ("abc", '"'),			"abc + \" - combine ()");
is ( $csv->string, 'abc,""""',				"abc + \" - string ()");
ok ( $csv->combine ("abc", "def", "ghi", "j,k"),	"abc .. j,k - combine ()");
is ( $csv->string, 'abc,def,ghi,"j,k"',			"abc .. j,k - string ()");
ok ( $csv->combine ("abc\tdef", "ghi"),			"abc + TAB - combine ()");
is ( $csv->string, qq("abc\tdef",ghi),			"abc + TAB - string ()");

ok (1,							"parse () tests");
ok (!$csv->parse (),					"Missing arguments");
ok ( $csv->parse ("\n"),				"Single newline");
ok (!$csv->parse ('"abc'),				"Missing closing \"");
ok (!$csv->parse ('ab"c'),				"\" outside of \"'s");
ok (!$csv->parse ('"ab"c"'),				"Bad character sequence");
ok (!$csv->parse (qq("abc\nc")),			"Bad character (NL)");
ok (!$csv->status (),					"Wrong status ()");
ok ( $csv->parse ('","'),				"comma - parse ()");
is ( scalar $csv->fields (), 1,				"comma - fields () - count");
is (($csv->fields ())[0], ",",				"comma - fields () - content");
ok ( $csv->parse (qq("","I said,\t""Hi!""","")),	"Hi! - parse ()");
is ( scalar $csv->fields (), 3,				"Hi! - fields () - count");

is (($csv->fields ())[0], "",				"Hi! - fields () - field 1");
is (($csv->fields ())[1], qq(I said,\t"Hi!"),		"Hi! - fields () - field 2");
is (($csv->fields ())[2], "",				"Hi! - fields () - field 3");
ok ( $csv->status (),					"status ()");

ok ( $csv->parse (""),					"Empty line");
is ( scalar $csv->fields (), 1,				"Empty - count");
is (($csv->fields ())[0], "",				"One empty field");

# Are Integers and Reals quoted?
#
#    Important: Do not modify these tests unless you have a good
#    reason. This file ought to guarantee compatibility to Text::CSV.
#
ok (1,							"Integers and Reals");
ok ( $csv->combine ("", 2, 3.25, "a", "a b"),		"Mixed - combine ()");
is ( $csv->string, ',2,3.25,a,"a b"',			"Mixed - string ()");

# New from object
ok ($csv->new (),					"\$csv->new ()");

my $state;
for ( [ 0, 0 ],
      [ 0, "foo" ],
      [ 0, {} ],
      [ 0, \0 ],
      [ 0, *STDOUT ],
      ) {
    eval { $state = $csv->print (@$_) };
    ok (!$state, "print needs (IO, ARRAY_REF)");
    ok ($@ =~ m/^Expected fields to be an array ref/, "Error msg");
    }

1;
