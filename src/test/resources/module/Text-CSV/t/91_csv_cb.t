#!/usr/bin/perl

use strict;
$^W = 1;

#use Test::More "no_plan";
 use Test::More tests => 82;

BEGIN {
    $ENV{PERL_TEXT_CSV} = $ENV{TEST_PERL_TEXT_CSV} || 0;
    use_ok "Text::CSV", ("csv");
    plan skip_all => "Cannot load Text::CSV" if $@;
    require "./t/util.pl";
    }

my $tfn  = "_91test.csv"; END { -f $tfn and unlink $tfn }
my $data =
    "foo,bar,baz\n".
    "1,2,3\n".
    "2,a b,\n";
open  my $fh, ">", $tfn or die "$tfn: $!";
print $fh $data;
close $fh;

my $aoa = [
    [qw( foo bar baz )],
    [ 1, 2, 3 ],
    [ 2, "a b", "" ],
    ];
my $aoh = [
    { foo => 1, bar => 2,     baz => 3  },
    { foo => 2, bar => "a b", baz => "" },
    ];

for (qw( after_in on_in before_out )) {
    is_deeply (csv (in => $tfn, $_ => sub {}), $aoa, "callback $_ on AOA with empty sub");
    is_deeply (csv (in => $tfn, callbacks => { $_ => sub {} }), $aoa, "callback $_ on AOA with empty sub");
    }
is_deeply (csv (in => $tfn, after_in => sub {},
    callbacks => { on_in => sub {} }), $aoa, "callback after_in and on_in on AOA");

for (qw( after_in on_in before_out )) {
    is_deeply (csv (in => $tfn, headers => "auto", $_ => sub {}), $aoh, "callback $_ on AOH with empty sub");
    is_deeply (csv (in => $tfn, headers => "auto", callbacks => { $_ => sub {} }), $aoh, "callback $_ on AOH with empty sub");
    }
is_deeply (csv (in => $tfn, headers => "auto", after_in => sub {},
    callbacks => { on_in => sub {} }), $aoh, "callback after_in and on_in on AOH");

is_deeply (csv (in => $tfn, after_in => sub { push @{$_[1]}, "A" }), [
    [qw( foo bar baz A )],
    [ 1, 2, 3, "A" ],
    [ 2, "a b", "", "A" ],
    ], "AOA ith after_in callback");

is_deeply (csv (in => $tfn, headers => "auto", after_in => sub { $_[1]{baz} = "A" }), [
    { foo => 1, bar => 2, baz => "A" },
    { foo => 2, bar => "a b", baz => "A" },
    ], "AOH with after_in callback");

is_deeply (csv (in => $tfn, filter => { 2 => sub { /a/ }}), [
    [qw( foo bar baz )],
    [ 2, "a b", "" ],
    ], "AOA with filter on col 2");
is_deeply (csv (in => $tfn, filter => { 2 => sub { /a/ },
					 1 => sub { length > 1 }}), [
    [qw( foo bar baz )],
    ], "AOA with filter on col 1 and 2");
is_deeply (csv (in => $tfn, filter => { foo => sub { $_ > 1 }}), [
    { foo => 2, bar => "a b", baz => "" },
    ], "AOH with filter on column name");

SKIP: {
    $] < 5.008001 and skip "No HOH/xx support in $]", 3;
    is_deeply (csv (in => $tfn, headers => "lc"),
		[ { foo => 1, bar => 2,     baz => 3 },
		  { foo => 2, bar => "a b", baz => "" }],
		"AOH with lc headers");
    is_deeply (csv (in => $tfn, headers => "uc"),
		[ { FOO => 1, BAR => 2,     BAZ => 3 },
		  { FOO => 2, BAR => "a b", BAZ => "" }],
		"AOH with lc headers");
    is_deeply (csv (in => $tfn, headers => sub { lcfirst uc $_[0] }),
		[ { fOO => 1, bAR => 2,     bAZ => 3 },
		  { fOO => 2, bAR => "a b", bAZ => "" }],
		"AOH with mangled headers");
    }

SKIP: {
    $] < 5.008001 and skip "No BOM support in $]", 1;
    is_deeply (csv (in => $tfn, munge => { bar => "boo" }),
	[{ baz =>  3, boo => 2,     foo => 1 },
	 { baz => "", boo => "a b", foo => 2 }], "Munge with hash");
    }

open  $fh, ">>", $tfn or die "$tfn: $!";
print $fh <<"EOD";
3,3,3
4,5,6
5,7,9
6,9,12
7,11,15
8,13,18
EOD
close $fh;

is_deeply (csv (in => $tfn,
	filter => { foo => sub { $_ > 2 && $_[1][2] - $_[1][1] < 4 }}), [
    { foo => 3, bar => 3, baz =>  3 },
    { foo => 4, bar => 5, baz =>  6 },
    { foo => 5, bar => 7, baz =>  9 },
    { foo => 6, bar => 9, baz => 12 },
    ], "AOH with filter on column name + on other numbered fields");

is_deeply (csv (in => $tfn,
	filter => { foo => sub { $_ > 2 && $_{baz}  - $_{bar}  < 4 }}), [
    { foo => 3, bar => 3, baz =>  3 },
    { foo => 4, bar => 5, baz =>  6 },
    { foo => 5, bar => 7, baz =>  9 },
    { foo => 6, bar => 9, baz => 12 },
    ], "AOH with filter on column name + on other named fields");

# Check content ref in on_in AOA
{   my $aoa = csv (
	in          => $tfn,
	filter      => { 1 => sub { m/^[3-9]/ }},
	on_in       => sub {
	    is ($_[1][1], 2 * $_[1][0] - 3, "AOA $_[1][0]: b = 2a - 3 \$_[1][]");
	    });
    }
# Check content ref in on_in AOH
{   my $aoa = csv (
	in          => $tfn,
	headers     => "auto",
	filter      => { foo => sub { m/^[3-9]/ }},
	after_parse => sub {
	    is ($_[1]{bar}, 2 * $_[1]{foo} - 3, "AOH $_[1]{foo}: b = 2a - 3 \$_[1]{}");
	    });
    }
# Check content ref in on_in AOH with aliases %_
SKIP: {
    $] < 5.008001 and skip "No AOH/alias support in $]", 7; # 6 in on_in, 1 is_deeply
    %_ = ( brt => 42 );
    my $aoa = csv (
	in          => $tfn,
	headers     => "auto",
	filter      => { foo => sub { m/^[3-9]/ }},
	on_in       => sub {
	    is ($_{bar}, 2 * $_{foo} - 3, "AOH $_{foo}: b = 2a - 3 \$_{}");
	    });
    is_deeply (\%_, { brt => 42 }, "%_ restored");
    }

SKIP: {
    $] < 5.008001 and skip "Too complicated test for $]", 2;
    # Add to %_ in callback
    # And test bizarre (but allowed) attribute combinations
    # Most of them can be either left out or done more efficiently in
    # a different way
    my $xcsv = Text::CSV->new;
    is_deeply (csv (in                 => $tfn,
		    seps               => [ ",", ";" ],
		    munge              => "uc",
		    quo                => '"',
		    esc                => '"',
		    csv                => $xcsv,
		    filter             => { 1 => sub { $_ eq "4" }},
		    on_in              => sub { $_{BRT} = 42; }),
		[{ FOO => 4, BAR => 5, BAZ => 6, BRT => 42 }],
		"AOH with addition to %_ in on_in");
    is_deeply ($xcsv->csv (
		    file               => $tfn,
		    sep_set            => [ ";", "," ],
		    munge_column_names => "uc",
		    quote_char         => '"',
		    quote              => '"',
		    escape_char        => '"',
		    escape             => '"',
		    filter             => { 1 => sub { $_ eq "4" }},
		    after_in           => sub { $_{BRT} = 42; }),
		[{ FOO => 4, BAR => 5, BAZ => 6, BRT => 42 }],
		"AOH with addition to %_ in on_in");
    }


SKIP: {
    $] < 5.008001 and skip "Too complicated test for $]", 2;
    ok (my $hr = csv (in => $tfn, key => "foo", on_in => sub {
			$_[1]{quz} = "B"; $_{ziq} = 2; }),
	"Get into hashref with key and on_in");
    is_deeply ($hr->{8}, {qw( bar 13 baz 18 foo 8 quz B ziq 2 )},
	"on_in with key works");
    }

open  $fh, ">", $tfn or die "$tfn: $!";
print $fh <<"EOD";
3,3,3

5,7,9
,
"",
,, ,
,"",
,," ",
""
8,13,18
EOD
close $fh;

SKIP: {
    $] < 5.008001 and skip "Too complicated test for $]", 4;
    is_deeply (csv (in => $tfn, filter => "not_blank"),
		[[3,3,3],[5,7,9],["",""],["",""],["",""," ",""],
		 ["","",""],["",""," ",""],[8,13,18]],
		"filter => not_blank");
    is_deeply (csv (in => $tfn, filter => "not_empty"),
		[[3,3,3],[5,7,9],["",""," ",""],["",""," ",""],[8,13,18]],
		"filter => not_empty");
    is_deeply (csv (in => $tfn, filter => "filled"),
		[[3,3,3],[5,7,9],[8,13,18]],
		"filter => filled");

    is_deeply (csv (in => $tfn, filter => sub {
		    grep { defined && m/\S/ } @{$_[1]} }),
		[[3,3,3],[5,7,9],[8,13,18]],
		"filter => filled");
    }

{   my @err;
    my $aoa = csv (in => $tfn, strict => 1, on_error => sub { @err = @_ });
    is_deeply ($aoa, [[3,3,3]], "Bad CSV still returns ref");
    is ($err[0], 2014, "ENF - Inconsistent number of fields");
    is (0 + Text::CSV->error_diag, 2014, "Error is kept");
    }

# Count rows in different ways
open  $fh, ">", $tfn or die "$tfn: $!";
print $fh <<"EOD";
foo,bar,baz
1,,3
0,"d
€",4
999,999,
EOD
close $fh;

{   my $n = 0;
    open my $fh, "<", $tfn;
    my $csv = Text::CSV->new ({ binary => 1 });
    while (my $row = $csv->getline ($fh)) { $n++; }
    close $fh;
    is ($n, 4, "Count rows with getline");
    }
{   my $n = 0;
    my $aoa = csv (in => $tfn, on_in => sub { $n++ });
    is ($n, 4, "Count rows with on_in");
    }
{   my $n = 0;
    my $aoa = csv (in => $tfn, filter => { 0 => sub { $n++; 0; }});
    is ($n, 4, "Count rows with filter hash");
    }
SKIP: {
    $] < 5.008001 and skip "Too complicated test for $]", 1;
    my $n = 0;
    my $aoa = csv (in => $tfn, filter => sub { $n++; 0; });
    is ($n, 4, "Count rows with filter sub");
    }
SKIP: {
    $] < 5.008001 and skip "Too complicated test for $]", 1;
    my $n = 0;
    csv (in => $tfn, on_in => sub { $n++; 0; }, out => \"skip");
    is ($n, 4, "Count rows with on_in and skipped out");
    }

# sep_set, seps, sep on problematic header
foreach my $sep (",", ";", "\t") {
    my $ph = "Problematic header";

    open  $fh, ">", $tfn or die "$tfn: $!";
    print $fh qq{foo${sep}"bar: a, b"${sep}"c;d"${sep}"e"\n};
    print $fh qq{1${sep}2${sep}3${sep}4\n};
    close $fh;

    my $exp = [{
	"foo"       => 1,
	"bar: a, b" => 2,
	"c;d"       => 3,
	"e"         => 4,
	}];

    ok (csv (in => $tfn, allow_loose_quotes => 1), "$ph, AoA");

    if ($] < 5.010000) {
	ok (1, "Unsupported header feature for $] - sep: $sep") for 1..6;
	next;
	}

    my @err;
    is (eval {
	local $SIG{__WARN__} = sub { push @err => @_ };
	csv (in => $tfn, bom => 1);
	}, undef, "$ph: cannot decide on sep");
    like ($err[0], qr{ERROR: 1011\b}, "$ph: error 1011");

    is_deeply (csv (in => $tfn, bom => 1, sep_set  => [ $sep ]), $exp, "$ph: sep_set");
    is_deeply (csv (in => $tfn, bom => 1, seps     => [ $sep ]), $exp, "$ph: seps");
    is_deeply (csv (in => $tfn, bom => 1, sep_char =>   $sep  ), $exp, "$ph: sep_char");
    is_deeply (csv (in => $tfn, bom => 1, sep      =>   $sep  ), $exp, "$ph: sep");
    }
