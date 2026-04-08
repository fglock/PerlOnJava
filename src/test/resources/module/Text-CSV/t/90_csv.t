#!/usr/bin/perl

use strict;
$^W = 1;
use Config;

#use Test::More "no_plan";
 use Test::More tests => 127;

BEGIN {
    $ENV{PERL_TEXT_CSV} = $ENV{TEST_PERL_TEXT_CSV} || 0;
    use_ok "Text::CSV", ("csv");
    plan skip_all => "Cannot load Text::CSV" if $@;
    require "./t/util.pl";
    }

my $tfn  = "_90test.csv"; END { -f $tfn and unlink $tfn }
my $data =
    "foo,bar,baz\n".
    "1,2,3\n".
    "2,a b,\n";
open  FH, ">", $tfn or die "$tfn: $!";
print FH $data;
close FH;

my @hdr = qw( foo bar baz );
my $aoa = [
    \@hdr,
    [ 1, 2, 3 ],
    [ 2, "a b", "" ],
    ];
my $aoh = [
    { foo => 1, bar => 2, baz => 3 },
    { foo => 2, bar => "a b", baz => "" },
    ];

SKIP: for my $io ([ $tfn, "file" ], [ \*FH, "globref" ], [ *FH, "glob" ], [ \$data, "ScalarIO"] ) {
    $] < 5.008 && ref $io->[0] eq "SCALAR" and skip "No ScalarIO support for $]", 1;
    open FH, "<", $tfn or die "$tfn: $!\n";
    is_deeply (csv ({ in => $io->[0] }), $aoa, "AOA $io->[1]");
    close FH;
    }

SKIP: for my $io ([ $tfn, "file" ], [ \*FH, "globref" ], [ *FH, "glob" ], [ \$data, "ScalarIO"] ) {
    $] < 5.008 && ref $io->[0] eq "SCALAR" and skip "No ScalarIO support for $]", 1;
    open FH, "<", $tfn or die "$tfn: $!\n";
    is_deeply (csv (in => $io->[0], headers => "auto"), $aoh, "AOH $io->[1]");
    close FH;
    }

is_deeply (csv (in => $tfn, headers => { bar => "tender" }), [
    { foo => 1, tender => 2,     baz => 3 },
    { foo => 2, tender => "a b", baz => "" },
    ], "AOH with header map");

my @aoa = @{$aoa}[1,2];
is_deeply (csv (file => $tfn, headers  => "skip"),    \@aoa, "AOA skip");
is_deeply (csv (file => $tfn, fragment => "row=2-3"), \@aoa, "AOA fragment");

if ($] >= 5.008001) {
    my @hdr;
    ok (my $ref = csv (in => $tfn, bom => 1), "csv (-- not keeping header)");
    is_deeply (\@hdr, [], "Should still be empty");
    foreach my $alias (qw( keep_headers keep_column_names kh )) {
	@hdr = ();
	ok (my $ref = csv (in => $tfn, bom => 1, $alias => \@hdr), "csv ($alias => ...)");
	is_deeply (\@hdr, [qw( foo bar baz )], "Headers kept for $alias");
	}
    foreach my $alias (qw( keep_headers keep_column_names kh )) {
	@hdr = ();
	ok (my $ref = csv (in => $tfn, $alias => \@hdr), "csv ($alias => ... -- implied headers)");
	is_deeply (\@hdr, [qw( foo bar baz )], "Headers kept for $alias");
	}
    foreach my $alias (qw( internal true yes 1 )) {
	my $buf = "";
	ok (my $ref = csv (in => $tfn, kh => $alias), "csv (kh => $alias)");
	ok (csv (in => $ref, out => \$buf, kh => $alias, quote_space => 0, eol => "\n"), "get it back");
	is ($buf, $data, "Headers kept for $alias");
	}
    }
else {
    ok (1, q{This perl cannot do scalar IO}) for 1..26;
    }

if ($] >= 5.008001) {
    is_deeply (csv (in => $tfn, encoding => "utf-8", headers => ["a", "b", "c"],
		    fragment => "row=2", sep_char => ","),
	   [{ a => 1, b => 2, c => 3 }], "AOH headers fragment");
    is_deeply (csv (in => $tfn, enc      => "utf-8", headers => ["a", "b", "c"],
		    fragment => "row=2", sep_char => ","),
	   [{ a => 1, b => 2, c => 3 }], "AOH headers fragment");
    }
else {
    ok (1, q{This perl does not support open with "<:encoding(...)"});
    ok (1, q{This perl does not support open with "<:encoding(...)"});
    }

ok (csv (in => $aoa, out => $tfn), "AOA out file");
is_deeply (csv (in => $tfn), $aoa, "AOA parse out");

ok (csv (in => $aoh, out => $tfn, headers => "auto"), "AOH out file");
is_deeply (csv (in => $tfn, headers => "auto"), $aoh, "AOH parse out");

if ($Config{usecperl} && $Config{usecperl} eq "define") {
    ok (1, "cperl has a different view on stable sorting of hash keys");
    ok (1, "not doing this (silly) test");
    }
else {
    ok (csv (in => $aoh, out => $tfn, headers => "skip"), "AOH out file no header");
    is_deeply (csv (in => $tfn, headers => [keys %{$aoh->[0]}]),
	$aoh, "AOH parse out no header");
    }

my $idx = 0;
sub getrowa { return $aoa->[$idx++]; }
sub getrowh { return $aoh->[$idx++]; }

ok (csv (in => \&getrowa, file => $tfn), "out via file from CODE/AR");
is_deeply (csv (in => $tfn), $aoa, "data from CODE/AR");

$idx = 0;
ok (csv (in => \&getrowh, out => $tfn, headers => \@hdr), "out from CODE/HR");
is_deeply (csv (in => $tfn, headers => "auto"), $aoh, "data from CODE/HR");

$idx = 0;
ok (csv (in => \&getrowh, out => $tfn), "out from CODE/HR (auto headers)");
is_deeply (csv (in => $tfn, headers => "auto"), $aoh, "data from CODE/HR");
unlink $tfn;

# Basic "key" checks
SKIP: {
    $] < 5.008 and skip "No ScalarIO support for $]", 4;
    # Simple key
    is_deeply (csv (in => \"key,value\n1,2\n", key => "key"),
		    { 1 => { key => 1, value => 2 }}, "key");
    is_deeply (csv (in => \"1,2\n", key => "key", headers => [qw( key value )]),
		    { 1 => { key => 1, value => 2 }}, "key");
    # Combined key
    is_deeply (csv (in => \"a,b,value\n1,1,2\n", key => [ ":" => "a", "b" ]),
		    { "1:1" => { a => 1, b => 1, value => 2 }}, "key list");
    is_deeply (csv (in => \"2,3,2\n", key => [ ":" => "a", "b" ], headers => [qw( a b value )]),
		    { "2:3" => { a => 2, b => 3, value => 2 }}, "key list");
    }
# Basic "value" checks
SKIP: {
    $] < 5.008001 and skip "No ScalarIO support for 'value's in $]", 5;
    # Simple key simple value
    is_deeply (csv (in => \"key,value\n1,2\n", key => "key", value => "value"),
		    { 1 => 2 }, "key:value");
    is_deeply (csv (in => \"1,2\n", key => "key", headers => [qw( key value )], value => "value"),
		    { 1 => 2 }, "key:value");
    # Simple key combined value
    is_deeply (csv (in => \"key,v1,v2\n1,2,3\n", key => "key", value => [ "v1", "v2" ]),
		    { 1 => { v1 => 2, v2 => 3 }}, "key:value");
    # Combined key simple value
    is_deeply (csv (in => \"a,b,value\n1,1,2\n", key => [ ":" => "a", "b" ], value => "value"),
		    { "1:1" => 2 }, "[key]:value");
    # Combined key combined value
    is_deeply (csv (in => \"a,b,v1,v2\n1,1,2,2\n", key => [ ":" => "a", "b" ], value => [ "v1", "v2" ]),
		    { "1:1" => { v1 => 2, v2 => 2 }}, "[key]:[value]");
    }

# Some "out" checks
my $crnl;
open my $fh, ">", $tfn or die "$tfn: $!\n";
csv (in => [{ a => 1 }], out => $fh);
csv (in => [{ a => 1 }], out => $fh, headers => undef);
csv (in => [{ a => 1 }], out => $fh, headers => "auto");
csv (in => [{ a => 1 }], out => $fh, headers => ["a"]);
csv (in => [{ b => 1 }], out => $fh, headers => { b => "a" });
close $fh;
{   open  $fh, "<", $tfn or die "$tfn: $!\n";
    my $dta = do {local $/; <$fh>};
    my @layers = eval { PerlIO::get_layers ($fh); };
    close $fh;
    if (grep m/crlf/ => @layers) {
	$dta =~ s/\n/\r\n/g;
	$crnl++;
	}
    is ($dta, "a\r\n1\r\n" x 5, "AoH to out");
    }

# check internal defaults
{
    my $ad = 1;

    sub check
    {
	my ($csv, $ar) = @_;
	is ($csv->auto_diag,	$ad,	"default auto_diag ($ad)");
	is ($csv->binary,	1,	"default binary");
	is ($csv->eol,		"\r\n",	"default eol");
	} # check

    # Note that 5.6.x writes to a *file* named SCALAR(0x50414A10)
    open my $fh, ">", \my $out or die "IO: $!\n";
    csv (in => [[1,2]], out => $fh, on_in => \&check);

    # Check that I can overrule auto_diag
    $ad = 0;
    csv (in => [[1,2]], out => $fh, on_in => \&check, auto_diag => 0,
	($] >= 5.008004 ? (encoding => "utf-8") : ()));
    }
$] < 5.008 and unlink glob "SCALAR(*)";

# errors
{   my $err = "";
    local $SIG{__DIE__} = sub { $err = shift; };
    my $r = eval { csv (in => undef); };
    is ($r, undef, "csv needs in or file");
    like ($err, qr{^usage:}, "error");
    $err = "";

    $r = eval { csv (in => $tfn, key => [ ":" ], auto_diag => 0); };
    $err =~ s{\s+at\s+\S+\s+line\s+\d+\.\r?\n?\Z}{};
    is ($r, undef, "Fail call with key with not enough fields");
    like ($err, qr{PRM.*unsupported type}, $err);
    $err = "";

    $r = eval { csv (in => $tfn, key => { "fx" => 1 }, auto_diag => 0); };
    $err =~ s{\s+at\s+\S+\s+line\s+\d+\.\r?\n?\Z}{};
    is ($r, undef, "Fail call with unsupported key type");
    like ($err, qr{PRM.*unsupported type}, $err);
    $err = "";

    $r = eval { csv (in => $tfn, key => sub { "foo" }, auto_diag => 0); };
    $err =~ s{\s+at\s+\S+\s+line\s+\d+\.\r?\n?\Z}{};
    is ($r, undef, "Fail call with bad unsupported type");
    like ($err, qr{PRM.*unsupported type}, $err);
    $err = "";

    $r = eval { csv (in => $tfn, key => "xyz", auto_diag => 0); };
    $err =~ s{\s+at\s+\S+\s+line\s+\d+\.\r?\n?\Z}{};
    is ($r, undef, "Fail call with nonexisting key");
    like ($err, qr{PRM.*xyz}, $err);
    $err = "";

    $r = eval { csv (in => $tfn, key => [ "x" ], auto_diag => 0); };
    $err =~ s{\s+at\s+\S+\s+line\s+\d+\.\r?\n?\Z}{};
    is ($r, undef, "Fail call with no key in keylist");
    like ($err, qr{PRM.*unsupported type}, $err);
    $err = "";

    $r = eval { csv (in => $tfn, key => [ ":", "a", "xyz" ], auto_diag => 0); };
    $err =~ s{\s+at\s+\S+\s+line\s+\d+\.\r?\n?\Z}{};
    is ($r, undef, "Fail call with nonexisting key in keylist");
    like ($err, qr{PRM.*xyz}, $err);
    $err = "";

    local $SIG{__DIE__}  = sub { $err = shift; };
    local $SIG{__WARN__} = sub { $err = shift; };
    foreach my $hr (42, "foo", \my %hr, sub { 42; }, *STDOUT) {
	$r = eval { csv (in => $tfn, kh => $hr, auto_diag => 0); };
	$err =~ s{\s+at\s+\S+\s+line\s+\d+\.\r?\n?\Z}{};
	is ($r, undef, "Fail call with bad keep_header type");
	like ($err, qr{PRM.*unsupported type}, $err);
	$err = "";
	}

#   $r = eval { csv (in => +{}, auto_diag => 0); };
#   is ($r, undef, "Cannot read from hashref");
#   like ($err, qr{No such file}i, "No such file or directory");
#   undef $err;

    $r = eval { csv (in => undef, auto_diag => 0); };
    $err =~ s{\s+at\s+\S+\s+line\s+\d+\.\r?\n?\Z}{};
    is ($r, undef, "Cannot read from undef");
    like ($err, qr{^usage}, "Remind them of correct syntax");
    $err = "";

    $r = eval { csv (in => "", auto_diag => 0); };
    $err =~ s{\s+at\s+\S+\s+line\s+\d+\.\r?\n?\Z}{};
    is ($r, undef, "Cannot read from empty");
    like ($err, qr{^usage}, "Remind them of correct syntax");
    $err = "";

    my $fn = "./dev/foo/bar/\x99\x99/\x88\x88/".
	(join "\x99" => map { chr (128 + int rand 128) } 0..100).".csv";
    $r = eval { csv (in => $fn, auto_diag => 0); };
    $err =~ s{\s+at\s+\S+\s+line\s+\d+\.\r?\n?\Z}{};
    is ($r, undef, "Cannot read from impossible file");
    like ($err, qr{/foo/bar}, "No such file or directory");
    $err = "";

    $r = eval { csv (in => [[1,2]], out => $fn, auto_diag => 0); };
    $err =~ s{\s+at\s+\S+\s+line\s+\d+\.\r?\n?\Z}{};
    is ($r, undef, "Cannot write to impossible file");
    like ($err, qr{/foo/bar}, "No such file or directory");
    $err = "";

    $r = eval { csv (); };
    is ($r, undef, "Needs arguments");
    like ($err, qr{^usage}i, "Don't know what to do");
    $err = "";

    my $x = sub { 42; };
    $r = eval { csv (in => $tfn, out => \$x, auto_diag => 0); };
    $err =~ s{\s+at\s+\S+\s+line\s+\d+\.\r?\n?\Z}{};
    is ($r, undef, "Cannot write to subref");
    like ($err, qr{Not a GLOB}i, "Not a GLOB");
    $err = "";

    SKIP: {
	$] < 5.008 and skip "$] does not support bom here", 2;
	$x = [[ 1, 2 ]]; # Add hashes to arrays
	$r = eval { csv (in => $tfn, out => $x, bom => 1); };
	$err =~ s{\s+at\s+\S+\s+line\s+\d+\.\r?\n?\Z}{};
	is ($r, undef, "Cannot add hashes to arrays");
	like ($err, qr{type mismatch}, "HASH != ARRAY");
	$err = "";
	}

    $x = [{ a => 1, b => 2 }]; # Add arrays to hashes
    $r = eval { csv (in => $tfn, out => $x); };
    $err =~ s{\s+at\s+\S+\s+line\s+\d+\.\r?\n?\Z}{};
    is ($r, undef, "Cannot add arrays to hashes");
    like ($err, qr{type mismatch}i, "ARRAY != HASH");
    $err = "";
    }

eval {
    exists  $Config{useperlio} &&
    defined $Config{useperlio} &&
    $] >= 5.008                &&
    $Config{useperlio} eq "define" or skip "No scalar ref in this perl", 5;
    my $out = "";
    open my $fh, ">", \$out or die "IO: $!\n";
    ok (csv (in => [[ 1, 2, 3 ]], out => $fh), "out to fh to scalar ref");
    is ($out, "1,2,3\r\n",	"Scalar out");
    $out = "";
    ok (csv (in => [[ 1, 2, 3 ]], out => \$out), "out to scalar ref");
    is ($out, "1,2,3\r\n",	"Scalar out");

    is_deeply (csv (in => \qq{1,"2 3"}, quo => undef, esc => undef),
	       [["1", q{"2 3"}]], "quo => undef");
    };

{   my $csv = Text::CSV->new ({ binary => 1, auto_diag => 1 });
    my $expect = [["a"],[1],["a"],[1],["a"],[1],["a"],[1],["a"],[1]];
    is_deeply ($csv->csv (in => $tfn),        $expect, "csv from object");
    is_deeply (csv (in => $tfn, csv => $csv), $expect, "csv from attribute");
    }

{   local *STDOUT;
    my $ofn = "_STDOUT.csv";

    open STDOUT, ">", $ofn or die "$ofn: $!\n";
    {	my @w;
	local $SIG{__WARN__} = sub { push @w => @_ };
	csv (in => $tfn, quote_always => 1, fragment => "row=1-2",
	    on_in => sub { splice @{$_[1]}, 1; }, eol => "\n");
	if ($crnl) {
	    is (scalar @w, 0, "CRNL layer found");
	    }
	else {
	    like ($w[0], qr/2016 - EOL/, "EOL mismatch");
	    }
	}
    close STDOUT;
    my $dta = do { local (@ARGV, $/) = $ofn; <> };
    is ($dta, qq{"a"\n"1"\n}, "Chained csv call inherited attributes");
    unlink $ofn;

    open STDOUT, ">", $ofn;
    csv (in => [[1,2]], out => *STDOUT, eol => "\n");
    close STDOUT;
    $dta = do { local (@ARGV, $/) = $ofn; <> };
    is ($dta, qq{1,2\n}, "out to *STDOUT");
    unlink $ofn;

    open STDOUT, ">", $ofn;
    csv (in => [[1,2]], out => \*STDOUT, eol => "\n");
    close STDOUT;
    $dta = do { local (@ARGV, $/) = $ofn; <> };
    is ($dta, qq{1,2\n}, "out to \\*STDOUT");
    unlink $ofn;

    open STDOUT, ">", $ofn;
    csv (in => []);
    close STDOUT;
    is (-s $ofn, 0, "No data results in an empty file");
    unlink $ofn;

    SKIP: {
	$] <= 5.008 and skip qq{$] does not support ScalarIO}, 6;
	my $aoa = [[ 1, 2 ]];
	is (csv (in => \"3,4", out => $aoa), $aoa, "return AOA");
	is_deeply ($aoa, [[ 1, 2 ], [ 3, 4 ]], "Add to AOA");

	my $aoh = [{ a => 1, b => 2 }];
	is (csv (in => \"a,b\n3,4", out => $aoh, bom => 1), $aoh, "return AOH");
	is_deeply ($aoa, [[ 1, 2 ], [ 3, 4 ]], "Add to AOH");

	my $ref = { 1 => { a => 1, b => 2 }};
	is (csv (in => \"a,b\n3,4", out => $ref, key => "a"), $ref, "return REF");
	is_deeply ($ref, { 1 => { a => 1, b => 2},
			   3 => { a => 3, b => 4},
			   }, "Add to keyed hash");
	}

    SKIP: {
	$] <= 5.008003 and skip qq{$] does not support ">:crlf"}, 1;
	open STDOUT, ">", $ofn; binmode STDOUT, ":crlf";
	csv (in => [[1,2]], out => \*STDOUT);
	close STDOUT;
	open my $oh, "<", $ofn or die "$ofn: $!\n";
	binmode $oh;
	$dta = do { local $/; <$oh> };
	is ($dta, qq{1,2\r\n}, "out to \\*STDOUT");
	unlink $ofn;
	}
    }
