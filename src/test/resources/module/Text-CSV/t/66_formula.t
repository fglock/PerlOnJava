#!/usr/bin/perl

use strict;
$^W = 1;

use Test::More tests => 119;

BEGIN {
    $ENV{PERL_TEXT_CSV} = $ENV{TEST_PERL_TEXT_CSV} || 0;
    use_ok "Text::CSV", ();
    plan skip_all => "Cannot load Text::CSV" if $@;
    }
my $tfn = "_66test.csv"; END { -f $tfn and unlink $tfn; }

ok (my $csv = Text::CSV->new,		"new");

is ($csv->formula,		"none",		"default");
is ($csv->formula (1),		"die",		"die");
is ($csv->formula ("die"),	"die",		"die");
is ($csv->formula (2),		"croak",	"croak");
is ($csv->formula ("croak"),	"croak",	"croak");
is ($csv->formula (3),		"diag",		"diag");
is ($csv->formula ("diag"),	"diag",		"diag");
is ($csv->formula (4),		"empty",	"empty");
is ($csv->formula ("empty"),	"empty",	"empty");
is ($csv->formula (""),		"empty",	"explicit empty");
is ($csv->formula (5),		"undef",	"undef");
is ($csv->formula ("undef"),	"undef",	"undef");
is ($csv->formula (undef),	"undef",	"explicit undef");
is ($csv->formula (sub { }),	"cb",		"callback");
is ($csv->formula (0),		"none",		"none");
is ($csv->formula ("none"),	"none",		"none");

is ($csv->formula_handling,		"none",		"default");
is ($csv->formula_handling ("DIE"),	"die",		"die");
is ($csv->formula_handling ("CROAK"),	"croak",	"croak");
is ($csv->formula_handling ("DIAG"),	"diag",		"diag");
is ($csv->formula_handling ("EMPTY"),	"empty",	"empty");
is ($csv->formula_handling ("UNDEF"),	"undef",	"undef");
is ($csv->formula_handling ("NONE"),	"none",		"none");

foreach my $f (-1, 9, "xxx", "DIAX", [], {}) {
    eval { $csv->formula ($f); };
    like ($@, qr/\bformula-handling '\Q$f\E' is not supported/, "$f in invalid");
    }

my %f = qw(
    0 none  none  none
    1 die   die   die
    2 croak croak croak
    3 diag  diag  diag
    4 empty empty empty
    5 undef undef undef
    );
foreach my $f (sort keys %f) {
    ok (my $p = Text::CSV->new ({ formula => $f }), "new with $f");
    is ($p->formula, $f{$f}, "Set to $f{$f}");
    }
eval { Text::CSV->new ({ formula => "xxx" }); };
like ($@, qr/\bformula-handling 'xxx' is not supported/, "xxx is invalid");

# TODO : $csv->formula (sub { 42; });

# Parser

my @data = split m/\n/ => <<"EOC";
a,b,c
1,2,3
=1+2,3,4
1,=2+3,4
1,2,=3+4
EOC

sub parse {
    my $f  = shift;
    my @d;
    ok (my $csv = Text::CSV->new ({ formula => $f }), "new $f");
    #diag ("Formula: ". $csv->formula);
    for (@data) {
	$csv->parse ($_);
	push @d, [ $csv->fields ];
	}
    \@d;
    } # parse

is_deeply (parse (0), [
    [ "a",	"b",	"c",	],
    [ "1",	"2",	"3",	],
    [ "=1+2",	"3",	"4",	],
    [ "1",	"=2+3",	"4",	],
    [ "1",	"2",	"=3+4",	],
    ], "Default");

my $r = eval { parse (1) };
is ($r, undef,				"Die on formulas");
is ($@, "Formulas are forbidden\n",	"Message");
$@ = undef;

   $r = eval { parse (2) };
is ($r, undef,				"Croak on formulas");
is ($@, "Formulas are forbidden\n",	"Message");
$@ = undef;

my @m;
local $SIG{__WARN__} = sub { push @m, @_ };

is_deeply (parse (3), [
    [ "a",	"b",	"c",	],
    [ "1",	"2",	"3",	],
    [ "=1+2",	"3",	"4",	],
    [ "1",	"=2+3",	"4",	],
    [ "1",	"2",	"=3+4",	],
    ], "Default");
is ($@, undef, "Legal with warnings");
is_deeply (\@m, [
    "Field 1 in record 3 contains formula '=1+2'\n",
    "Field 2 in record 4 contains formula '=2+3'\n",
    "Field 3 in record 5 contains formula '=3+4'\n",
    ], "Warnings");
@m = ();

is_deeply (parse (4), [
    [ "a",	"b",	"c",	],
    [ "1",	"2",	"3",	],
    [ "",	"3",	"4",	],
    [ "1",	"",	"4",	],
    [ "1",	"2",	"",	],
    ], "Empty");

is_deeply (parse (5), [
    [ "a",	"b",	"c",	],
    [ "1",	"2",	"3",	],
    [ undef,	"3",	"4",	],
    [ "1",	undef,	"4",	],
    [ "1",	"2",	undef,	],
    ], "Undef");

for ([ "Callback return",	sub {      42; }	],
     [ "Callback assign",	sub { $_ = 42; }	],
     [ "Callback subst",	sub { s/.*/42/; $_ }	], # s///r requires 5.13.2
     ) {
    my ($msg, $cb) = @$_;
    is_deeply (parse ($cb), [
	[ "a",	"b",	"c",	],
	[ "1",	"2",	"3",	],
	[ "42",	"3",	"4",	],
	[ "1",	"42",	"4",	],
	[ "1",	"2",	"42",	],
	], $msg);
    }
is_deeply (parse (sub { eval { s{^=([-+*/0-9()]+)$}{$1}ee }; $_ }), [
    [ "a",	"b",	"c",	],
    [ "1",	"2",	"3",	],
    [ "3",	"3",	"4",	],
    [ "1",	"5",	"4",	],
    [ "1",	"2",	"7",	],
    ], "Callback calculations");

{   @m = ();
    ok (my $csv = Text::CSV->new ({ formula => 3 }), "new 3 hr");
    ok ($csv->column_names ("code", "value", "desc"), "Set column names");
    ok ($csv->parse ("1,=2+3,4"), "Parse");
    is_deeply (\@m,
	[ qq{Field 2 (column: 'value') contains formula '=2+3'\n} ],
	"Warning for HR");
    }

# Writer

sub writer {
    my $f = shift;
    ok (my $csv = Text::CSV->new ({
	formula_handling => $f, quote_empty => 1 }), "new $f");
    ok ($csv->combine ("1", "=2+3", "4"), "combine $f");
    $csv->string;
    } # writer

@m = ();
is (       writer (0),		q{1,=2+3,4}, "Out 0");
is (eval { writer (1) },	undef,       "Out 1");
is (eval { writer (2) },	undef,       "Out 2");
is (       writer (3),		q{1,=2+3,4}, "Out 3");
is (       writer (4),		q{1,"",4},   "Out 4");
is (       writer (5),		q{1,,4},     "Out 5");
is_deeply (\@m,  [ "Field 1 contains formula '=2+3'\n" ], "Warning 3");

@m = ();
is (       writer ("none"),	q{1,=2+3,4}, "Out none");
is (eval { writer ("die") },	undef,       "Out die");
is (eval { writer ("croak") },	undef,       "Out croak");
is (       writer ("diag"),	q{1,=2+3,4}, "Out diag");
is (       writer ("empty"),	q{1,"",4},   "Out empty");
is (       writer ("undef"),	q{1,,4},     "Out undef");
is_deeply (\@m,  [ "Field 1 contains formula '=2+3'\n" ], "Warning diag");

open my $fh, ">", $tfn;
printf $fh <<"EOC";
1,2,3
=1+2,3,4
1,=12-6,5
1,2,=4+(9-1)/2
EOC
close $fh;

is_deeply (Text::CSV::csv (in => $tfn,
	    formula => sub { eval { s{^=([-+*/0-9()]+)$}{$1}ee }; $_ }),
    [[1,2,3],[3,3,4],[1,6,5],[1,2,8]], "Formula calc from csv function");
