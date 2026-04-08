#!/usr/bin/perl

use strict;
$^W = 1;	# use warnings core since 5.6

use Test::More tests => 245;

BEGIN {
    $ENV{PERL_TEXT_CSV} = $ENV{TEST_PERL_TEXT_CSV} || 0;
    use_ok "Text::CSV";
    plan skip_all => "Cannot load Text::CSV" if $@;
    }

my $Backend = Text::CSV->backend;

my $csv;
ok ($csv = Text::CSV->new,				"new ()");

is ($csv->quote_char,			'"',		"quote_char");
is ($csv->quote,			'"',		"quote");
is ($csv->escape_char,			'"',		"escape_char");
is ($csv->sep_char,			",",		"sep_char");
is ($csv->sep,				",",		"sep");
is ($csv->eol,				"",		"eol");
is ($csv->always_quote,			0,		"always_quote");
is ($csv->binary,			0,		"binary");
is ($csv->keep_meta_info,		0,		"keep_meta_info");
is ($csv->allow_loose_quotes,		0,		"allow_loose_quotes");
is ($csv->allow_loose_escapes,		0,		"allow_loose_escapes");
is ($csv->allow_unquoted_escape,	0,		"allow_unquoted_escape");
is ($csv->allow_whitespace,		0,		"allow_whitespace");
is ($csv->blank_is_undef,		0,		"blank_is_undef");
is ($csv->empty_is_undef,		0,		"empty_is_undef");
is ($csv->auto_diag,			0,		"auto_diag");
is ($csv->diag_verbose,			0,		"diag_verbose");
is ($csv->verbatim,			0,		"verbatim");
is ($csv->formula,			"none",		"formula");
is ($csv->strict,			0,		"strict");
is ($csv->strict_eol,			0,		"strict_eol");
is ($csv->skip_empty_rows,		0,		"skip_empty_rows");
is ($csv->quote_space,			1,		"quote_space");
is ($csv->quote_empty,			0,		"quote_empty");
is ($csv->escape_null,			1,		"escape_null");
is ($csv->quote_null,			1,		"quote_null");
is ($csv->quote_binary,			1,		"quote_binary");
is ($csv->record_number,		0,		"record_number");
is ($csv->decode_utf8,			1,		"decode_utf8");
is ($csv->undef_str,			undef,		"undef_str");
is ($csv->comment_str,			undef,		"comment_str");

is ($csv->binary (1),			1,		"binary (1)");
my @fld = ( 'txt =, "Hi!"', "Yes", "", 2, undef, "1.09", "\r", undef );
ok ($csv->combine (@fld),				"combine");
is ($csv->string,
    qq{"txt =, ""Hi!""",Yes,,2,,1.09,"\r",},	"string");

is ($csv->sep_char (";"),		";",		"sep_char (;)");
is ($csv->sep ("**"),			"**",		"sep (**)");
is ($csv->sep (";"),			";",		"sep (;)");
is ($csv->sep_char (),			";",		"sep_char ()");
is ($csv->quote_char ("="),		"=",		"quote_char (=)");
is ($csv->quote_char (undef),		undef,		"quote_char (undef)");
is ($csv->{quote_char},			undef,		"{quote_char} (undef)");
is ($csv->quote (undef),		"",		"quote (undef)");
is ($csv->quote (""),			"",		"quote (undef)");
is ($csv->quote ("**"),			"**",		"quote (**)");
is ($csv->quote ("="),			"=",		"quote (=)");
is ($csv->eol (undef),			"",		"eol (undef)");
is ($csv->eol (""),			"",		"eol ('')");
is ($csv->eol ("\r"),			"\r",		"eol (\\r)");
is ($csv->keep_meta_info (1),		1,		"keep_meta_info (1)");
is ($csv->keep_meta_info (0),		0,		"keep_meta_info (0)");
is ($csv->keep_meta_info (""),		0,		"keep_meta_info ('')");
is ($csv->keep_meta_info (undef),	0,		"keep_meta_info (undef)");
is ($csv->keep_meta_info ("false"),	0,		"keep_meta_info (undef)");
is ($csv->keep_meta_info ("true"),	1,		"keep_meta_info (undef)");
is ($csv->always_quote (undef),		0,		"always_quote (undef)");
is ($csv->always_quote (1),		1,		"always_quote (1)");
is ($csv->allow_loose_quotes (1),	1,		"allow_loose_quotes (1)");
is ($csv->allow_loose_escapes (1),	1,		"allow_loose_escapes (1)");
is ($csv->allow_unquoted_escape (1),	1,		"allow_unquoted_escape (1)");
is ($csv->allow_whitespace (1),		1,		"allow_whitespace (1)");
is ($csv->blank_is_undef (1),		1,		"blank_is_undef (1)");
is ($csv->empty_is_undef (1),		1,		"empty_is_undef (1)");
is ($csv->auto_diag (1),		1,		"auto_diag (1)");
is ($csv->auto_diag (2),		2,		"auto_diag (2)");
is ($csv->auto_diag (9),		9,		"auto_diag (9)");
is ($csv->auto_diag ("true"),		1,		"auto_diag (\"true\")");
is ($csv->auto_diag ("false"),		0,		"auto_diag (\"false\")");
is ($csv->auto_diag (undef),		0,		"auto_diag (undef)");
is ($csv->auto_diag (""),		0,		"auto_diag (\"\")");
is ($csv->diag_verbose (1),		1,		"diag_verbose (1)");
is ($csv->diag_verbose (2),		2,		"diag_verbose (2)");
is ($csv->diag_verbose (9),		9,		"diag_verbose (9)");
is ($csv->diag_verbose ("true"),	1,		"diag_verbose (\"true\")");
is ($csv->diag_verbose ("false"),	0,		"diag_verbose (\"false\")");
is ($csv->diag_verbose (undef),		0,		"diag_verbose (undef)");
is ($csv->diag_verbose (""),		0,		"diag_verbose (\"\")");
is ($csv->verbatim (1),			1,		"verbatim (1)");
is ($csv->formula ("diag"),		"diag",		"formula (\"diag\")");
is ($csv->strict (1),			1,		"strict (1)");
is ($csv->strict_eol (1),		1,		"strict_eol (1)");
is ($csv->skip_empty_rows (1),		1,		"skip_empty_rows (1)");
is ($csv->quote_space (1),		1,		"quote_space (1)");
is ($csv->quote_empty (1),		1,		"quote_empty (1)");
is ($csv->escape_null (1),		1,		"escape_null (1)");
is ($csv->quote_null (1),		1,		"quote_null (1)");
is ($csv->quote_binary (1),		1,		"quote_binary (1)");
is ($csv->escape_char (undef),		undef,		"escape_char (undef)");
is ($csv->{escape_char},		undef,		"{escape_char} (undef)");
is ($csv->escape_char ("\\"),		"\\",		"escape_char (\\)");
ok ($csv->combine (@fld),				"combine");
is ($csv->string,
    qq{=txt \\=, "Hi!"=;=Yes=;==;=2=;;=1.09=;=\r=;\r},	"string");
is ($csv->undef_str ("-"),		"-",		"undef_str");
is ($csv->comment_str ("#"),		"#",		"comment_str");

is ($csv->allow_whitespace (0),		0,		"allow_whitespace (0)");
is ($csv->quote_space (0),		0,		"quote_space (0)");
is ($csv->quote_empty (0),		0,		"quote_empty (0)");
is ($csv->escape_null (0),		0,		"escape_null (0)");
is ($csv->quote_null (0),		0,		"quote_null (0)");
is ($csv->quote_binary (0),		0,		"quote_binary (0)");
is ($csv->decode_utf8 (0),		0,		"decode_utf8 (0)");
is ($csv->sep ("--"),			"--",		"sep (\"--\")");
is ($csv->sep_char (),			"\0",		"sep_char");
is ($csv->quote ("++"),			"++",		"quote (\"++\")");
is ($csv->quote_char (),		"\0",		"quote_char");
is ($csv->undef_str (undef),		undef,		"undef_str");
is ($csv->comment_str (undef),		undef,		"comment_str");

# Test single-byte specials in UTF-8 mode
is ($csv->sep ("|"),			"|",		"sep |");
is ($csv->sep_char (),			"|",		"sep_char");
chop (my $s = "|\x{20ac}");
is ($csv->sep ($s),			"|",		"sep |");
is ($csv->sep (),			"|",		"sep_char");
is ($csv->sep_char (),			"|",		"sep_char");
is ($csv->quote ("'"),			"'",		"quote '");
is ($csv->quote_char (),		"'",		"quote_char");
chop (my $q = "'\x{20ac}");
is ($csv->quote ($q),			"'",		"quote '");
is ($csv->quote (),			"'",		"quote_char");
is ($csv->quote_char (),		"'",		"quote_char");

# Funny settings, all three translate to \0 internally
ok ($csv = Text::CSV->new ({
    sep		=> "::::::::::",
    quote_char	=> undef,
    escape_char	=> undef,
    }),						"new (undef ...)");
is ($csv->sep_char,		"\0",		"sep_char undef");
is ($csv->sep,			"::::::::::",	"sep long");
is ($csv->quote_char,		undef,		"quote_char undef");
is ($csv->quote,		undef,		"quote undef");
is ($csv->escape_char,		undef,		"escape_char undef");
ok ($csv->parse ("foo"),			"parse (foo)");
$csv->sep_char (",");
is ($csv->record_number,	1,		"record_number");
ok ($csv->parse ("foo"),			"parse (foo)");
is ($csv->record_number,	2,		"record_number");
ok (!$csv->parse ("foo,foo\0bar"),		"parse (foo)");
$csv->escape_char ("\\");
ok (!$csv->parse ("foo,foo\0bar"),		"parse (foo)");
$csv->binary (1);
ok ( $csv->parse ("foo,foo\0bar"),		"parse (foo)");

# Attribute aliasses
ok ($csv = Text::CSV->new ({ quote_always => 1, verbose_diag => 1}));
is ($csv->always_quote, 1,	"always_quote = quote_always");
is ($csv->diag_verbose, 1,	"diag_verbose = verbose_diag");
ok ($csv = Text::CSV->new ({ escape_char => undef }), "undef escape aliases");
is ($csv->escape_char, undef,	"escape_char is undef");
ok ($csv = Text::CSV->new ({ quote  => undef }), "undef quote aliases");
is ($csv->quote_char,  undef,	"quote_char is undef");
is ($csv->quote,       undef,	"quote is undef");

# Some forbidden combinations
foreach my $ws (" ", "\t") {
    ok ($csv = Text::CSV->new ({ escape_char => $ws }), "New blank escape");
    eval { ok ($csv->allow_whitespace (1), "Allow ws") };
    is (($csv->error_diag)[0], 1002, "Wrong combo");
    ok ($csv = Text::CSV->new ({ quote_char  => $ws }), "New blank quote");
    eval { ok ($csv->allow_whitespace (1), "Allow ws") };
    is (($csv->error_diag)[0], 1002, "Wrong combo");
    ok ($csv = Text::CSV->new ({ allow_whitespace => 1 }), "New ws 1");
    eval { ok ($csv->escape_char ($ws),     "esc") };
    is (($csv->error_diag)[0], 1002, "Wrong combo");
    ok ($csv = Text::CSV->new ({ allow_whitespace => 1 }), "New ws 1");
    eval { ok ($csv->quote_char  ($ws),     "esc") };
    is (($csv->error_diag)[0], 1002, "Wrong combo");
    }
foreach my $esc (undef, "", " ", "\t", "!!!!!!") {
    foreach my $quo (undef, "", " ", "\t", "!!!!!!") {
	defined $esc && $esc =~ m/[ \t]/ or 
	defined $quo && $quo =~ m/[ \t]/ or next;
	my $wc = join " " => map {
		!defined $_ ? "<undef>" :
		$_ eq ""    ? "<empty>" :
		$_ eq " "   ? "<sp>"    :
		$_ eq "\t"  ? "<tab>"   : $_ }
	    "esc:", $esc, "quo:", $quo;
	eval { $csv = Text::CSV->new ({
	    escape           => $esc,
	    quote            => $quo,
	    allow_whitespace => 1,
	    }) };
	like ((Text::CSV::error_diag)[1], qr{^INI - allow_whitespace}, "Wrong combo - error message: $wc");
	is   ((Text::CSV::error_diag)[0], 1002, "Wrong combo - numeric error: $wc");
	}
    }

# Test 1003 in constructor
foreach my $x ("\r", "\n", "\r\n", "x\n", "\rx") {
    foreach my $attr (qw( sep_char quote_char escape_char )) {
	#ok (1, "attr: $attr => ", $x =~ s/\n/\\n/gr =~ s/\r/\\r/gr);
	eval { $csv = Text::CSV->new ({ $attr => $x }) };
	is ((Text::CSV::error_diag)[0], 1003, "eol in $attr");
	}
    }
# Test 1003 in methods
foreach my $attr (qw( sep_char quote_char escape_char )) {
    ok ($csv = Text::CSV->new, "New");
    eval { ok ($csv->$attr ("\n"), "$attr => \\n") };
    is (($csv->error_diag)[0], 1003, "not allowed");
    }

# Too long attr (max 16)
$csv = Text::CSV->new ({ quote => "'" });
my $xl = "X" x 32;
eval { $csv->eol ($xl); };
is (($csv->error_diag)[0],		1005,	"eol too long");
is ($csv->eol (),			"",	"eol unchanged");
eval { $csv->sep ($xl); };
is (($csv->error_diag)[0],		1006,	"sep too long");
is ($csv->sep (),			",",	"sep unchanged");
eval { $csv->quote ($xl); };
is (($csv->error_diag)[0],		1007,	"quo too long");
is ($csv->quote (),			"'",	"quo unchanged");
eval { $csv = Text::CSV->new ({ eol   => $xl }); };
is ($csv,				undef,	"new with EOL too long");
is ((Text::CSV::error_diag)[0],	1005,	"error set");
eval { $csv = Text::CSV->new ({ sep   => $xl }); };
is ($csv,				undef,	"new with SEP too long");
is ((Text::CSV::error_diag)[0],	1006,	"error set");
eval { $csv = Text::CSV->new ({ quote => $xl }); };
is ($csv,				undef,	"new with QUO too long");
is ((Text::CSV::error_diag)[0],	1007,	"error set");

# And test erroneous calls
is (Text::CSV::new (0),		   undef,	"new () as function");
is (Text::CSV::error_diag (), "usage: my \$csv = $Backend->new ([{ option => value, ... }]);",
							"Generic usage () message");
is (Text::CSV->new ({ oel     => "" }), undef,	"typo in attr");
is (Text::CSV::error_diag (), "INI - Unknown attribute 'oel'",	"Unsupported attr");
is (Text::CSV->new ({ _STATUS => "" }), undef,	"private attr");
is (Text::CSV::error_diag (), "INI - Unknown attribute '_STATUS'",	"Unsupported private attr");

foreach my $arg (undef, 0, "", " ", 1, [], [ 0 ], *STDOUT) {
    is  (Text::CSV->new ($arg),         undef,	"Illegal type for first arg");
    is ((Text::CSV::error_diag)[0], 1000, "Should be a hashref - numeric error");
    }

my $attr = [ sort qw(
    eol
    sep_char sep quote_char quote escape_char
    binary decode_utf8
    auto_diag diag_verbose
    blank_is_undef empty_is_undef
    allow_whitespace allow_loose_quotes allow_loose_escapes allow_unquoted_escape
    always_quote quote_space quote_empty quote_binary
    escape_null
    keep_meta_info
    verbatim strict strict_eol skip_empty_rows formula
    undef_str comment_str
    types
    callbacks
    ENCODING
    )];
is_deeply ([ Text::CSV::known_attributes () ],      $attr, "Known attributes (function)");
is_deeply ([ Text::CSV->known_attributes () ],      $attr, "Known attributes (class method)");
is_deeply ([ Text::CSV->new->known_attributes () ], $attr, "Known attributes (method)");

1;
