#!/usr/bin/perl

use strict;
$^W = 1;

use Test::More tests => 25119;

BEGIN {
    $ENV{PERL_TEXT_CSV} = $ENV{TEST_PERL_TEXT_CSV} || 0;
    require_ok "Text::CSV";
    plan skip_all => "Cannot load Text::CSV" if $@;
    require "./t/util.pl";
    }

my $csv = Text::CSV->new ({ binary => 1 });

my @attrib  = qw( quote_char escape_char sep_char );
my @special = ('"', "'", ",", ";", "\t", "\\", "~");
# Add undef, once we can return undef
my @input   = ( "", 1, "1", 1.4, "1.4", " - 1,4", "1+2=3", "' ain't it great '",
    '"foo"! said the `bär', q{the ~ in "0 \0 this l'ne is \r ; or "'"} );
my $ninput  = scalar @input;
my $string  = join "=", "", @input, "";
my %fail;

ok (1, "--     qc     ec     sc     ac");
sub combi {
    my %attr = @_;
    my $combi = join " ", "--",
	map { sprintf "%6s", _readable $attr{$_} } @attrib, "always_quote";
    ok (1, $combi);

    # use legal non-special characters
    is ($csv->allow_whitespace (0), 0, "Reset allow WS");
    is ($csv->sep_char    ("\x03"), "\x03", "Reset sep");
    is ($csv->quote_char  ("\x0b"), "\x0b", "Reset quo");
    is ($csv->escape_char ("\x0c"), "\x0c", "Reset esc");

    # Set the attributes and check failure
    my %state;
    foreach my $attr (sort keys %attr) {
	eval { $csv->$attr ($attr{$attr}); };
	$@ or next;
	$state{0 + $csv->error_diag} ||= $@;
	}
    if ($attr{sep_char} eq $attr{quote_char} ||
	$attr{sep_char} eq $attr{escape_char}) {
	ok (exists $state{1001}, "Illegal combo");
	like ($state{1001}, qr{sep_char is equal to}, "Illegal combo");
	}
    else {
	ok (!exists $state{1001}, "No char conflict");
	}
    if (!exists $state{1001} and
	    $attr{sep_char}    =~ m/[\r\n]/ ||
	    $attr{quote_char}  =~ m/[\r\n]/ ||
	    $attr{escape_char} =~ m/[\r\n]/
	    ) {
	ok (exists $state{1003}, "Special contains eol");
	like ($state{1003}, qr{in main attr not}, "Illegal combo");
	}
    if ($attr{allow_whitespace} and
	    $attr{quote_char}  =~ m/^[ \t]/ ||
	    $attr{escape_char} =~ m/^[ \t]/
	    ) {
	#diag (join " -> ** " => $combi, join ", " => sort %state);
	ok (exists $state{1002}, "Illegal combo under allow_whitespace");
	like ($state{1002}, qr{allow_whitespace with}, "Illegal combo");
	}
    %state and return;

    # Check success
    is ($csv->$_ (), $attr{$_},  "check $_") for sort keys %attr;

    my $ret = $csv->combine (@input);

    ok ($ret, "combine");
    ok (my $str = $csv->string, "string");
    SKIP: {
	ok (my $ok = $csv->parse ($str), "parse");

	unless ($ok) {
	    $fail{parse}{$combi} = $csv->error_input;
	    skip "parse () failed",  3;
	    }

	ok (my @ret = $csv->fields, "fields");
	unless (@ret) {
	    $fail{fields}{$combi} = $csv->error_input;
	    skip "fields () failed", 2;
	    }

	is (scalar @ret, $ninput,   "$ninput fields");
	unless (scalar @ret == $ninput) {
	    $fail{'$#fields'}{$combi} = $str;
	    skip "# fields failed",  1;
	    }

	my $ret = join "=", "", @ret, "";
	is ($ret, $string,          "content");
	}
    } # combi

foreach my $aw (0, 1) {
foreach my $aq (0, 1) {
foreach my $qc (@special) {
foreach my $ec (@special, "+") {
foreach my $sc (@special, "\0") {
    combi (
	sep_char         => $sc,
	quote_char       => $qc,
	escape_char      => $ec,
	always_quote     => $aq,
	allow_whitespace => $aw,
	);
     }
    }
   }
  }
 }

foreach my $fail (sort keys %fail) {
    print STDERR "Failed combi for $fail ():\n",
		 "--     qc     ec     sc     ac\n";
    foreach my $combi (sort keys %{$fail{$fail}}) {
	printf STDERR "%-20s - %s\n", map { _readable $_ } $combi, $fail{$fail}{$combi};
	}
    }

{   my $err = "";
    local $SIG{__WARN__} = sub { $err = shift; };
    is (Text::CSV->new ({ sep => ",", quote => ",", auto_diag => 1 }),
	undef, "New (illegal combo + auto_diag)");
    like ($err, qr{\bERROR: 1001 - INI -}, "Error message");

    $err = "";
    ok (my $csv = Text::CSV->new ({ auto_diag => 1 }), "new auto_diag");
    eval { $csv->sep ('"'); };
    like ($err, qr{\bERROR: 1001 - INI -}, "Error message");
    is ($csv->sep_char (), '"', "sep changed anyway");
    }

{   ok (my $csv = Text::CSV->new ({ binary => 1 }), "New CSV default");
    ok ($csv->combine ("=\x00="), "combine =\\x00=");
    is ($csv->string, qq{"="0="}, "string");
    }
{   ok (my $csv = Text::CSV->new ({ binary => 1, escape_null => 0 }), "New CSV no escape_null");
    ok ($csv->combine ("=\x00="), "combine =\\x00=");
    is ($csv->string, qq{"=\0="}, "string");
    }
{   ok (my $csv = Text::CSV->new ({ binary => 1, escape_char => "" }), "New CSV no escape");
    ok ($csv->combine ("=\x00="), "combine =\\x00=");
    is ($csv->string, qq{"=\0="}, "string");
    }
{   ok (my $csv = Text::CSV->new ({ binary => 1, escape_char => "", escape_null => 0 }), "New CSV no escape no escape_null");
    ok ($csv->combine ("=\x00="), "combine =\\x00=");
    is ($csv->string, qq{"=\0="}, "string");
    }
1;
