#!/usr/bin/perl

use strict;
$^W = 1;

use Test::More;

BEGIN {
    if ($] < 5.008001) {
        plan skip_all => "This test unit requires perl-5.8.1 or higher";
	}
    else {
	plan tests => 56;
	}

    $ENV{PERL_TEXT_CSV} = $ENV{TEST_PERL_TEXT_CSV} || 0;

    use_ok "Text::CSV", ("csv");
    plan skip_all => "Cannot load Text::CSV" if $@;
    }
my $tfn = "_67test.csv"; END { -f $tfn and unlink $tfn; }

ok (my $csv = Text::CSV->new,			"new");

is ($csv->skip_empty_rows,		0,		"default");
is ($csv->skip_empty_rows (1),		1,		"+1");
is ($csv->skip_empty_rows ("skip"),	1,		"skip");
is ($csv->skip_empty_rows ("SKIP"),	1,		"SKIP");
is ($csv->skip_empty_rows (2),		"eof",		"+2");
is ($csv->skip_empty_rows ("eof"),	"eof",		"eof");
is ($csv->skip_empty_rows ("EOF"),	"eof",		"EOF");
is ($csv->skip_empty_rows ("stop"),	"eof",		"stop");
is ($csv->skip_empty_rows ("STOP"),	"eof",		"STOP");
is ($csv->skip_empty_rows (3),		"die",		"+3");
is ($csv->skip_empty_rows ("die"),	"die",		"die");
is ($csv->skip_empty_rows ("DIE"),	"die",		"DIE");
is ($csv->skip_empty_rows (4),		"croak",	"+4");
is ($csv->skip_empty_rows ("croak"),	"croak",	"croak");
is ($csv->skip_empty_rows ("CROAK"),	"croak",	"CROAK");
is ($csv->skip_empty_rows (5),		"error",	"+5");
is ($csv->skip_empty_rows ("error"),	"error",	"error");
is ($csv->skip_empty_rows ("ERROR"),	"error",	"ERROR");

sub cba { [      3,      42,      undef,      3 ] }
sub cbh { { a => 3, b => 42, c => undef, d => 3 } }

is ($csv->skip_empty_rows (\&cba),	\&cba,		"callback");

is ($csv->skip_empty_rows (0),		0,		"+0");
is ($csv->skip_empty_rows (undef),	0,		"undef");

open my $fh, ">", $tfn or BAIL_OUT "$tfn: $!\n";
print $fh "a,b,c,d\n";
print $fh "1,2,0,4\n";
print $fh "4,0,9,1\n";
print $fh "\n";
print $fh "8,2,7,1\n";
print $fh "\n";
print $fh "\n";
print $fh "5,7,9,3\n";
print $fh "\n";
close $fh;

my @parg = (auto_diag => 0, in => $tfn);
my @head = ([qw( a b c d )], [1,2,0,4], [4,0,9,1]);
my @repl = (1..4);
my $ea   = \@repl;

# Array behavior
is_deeply (csv (@parg, skip_empty_rows => 0), [ @head,
    [""],[8,2,7,1],[""],[""],[5,7,9,3],[""]],			"A Default");

is_deeply (csv (@parg, skip_empty_rows => 1), [ @head,
    [8,2,7,1],[5,7,9,3]],					"A Skip");

is_deeply (csv (@parg, skip_empty_rows => 2),  \@head,		"A EOF");

is (eval { csv (@parg, skip_empty_rows => 3); }, undef,		"A die");
like ($@, qr{^Empty row},					"A msg");

is (eval { csv (@parg, skip_empty_rows => 4); }, undef,		"A croak");
like ($@, qr{^Empty row},					"A msg");

$@ = "";
$csv = Text::CSV->new ({ skip_empty_rows => 5 });
is_deeply ($csv->csv (@parg), \@head,				"A error");
is ($@, "",							"A msg");
is (0 + $csv->error_diag, 2015,					"A code");

is_deeply (csv (@parg, skip_empty_rows => sub {\@repl}), [ @head,
    $ea,[8,2,7,1],$ea,$ea,[5,7,9,3],$ea],			"A Callback");
is_deeply (csv (@parg, skip_empty_rows => sub {0}), \@head,	"A Callback 0");

# Array behavior (line by line)
open $fh, "<", $tfn;
$csv = Text::CSV->new ({ skip_empty_rows => 1 });
while (my $row = $csv->getline ($fh)) {
    ok (@$row, "Row has columns");
    }
close $fh;

# Hash behavior
push @parg => bom => 1;
my $eh = { a => "", b => undef, c => undef, d => undef },
@head = ({ a => 1,  b => 2,     c => 0,     d => 4 },
	 { a => 4,  b => 0,     c => 9,     d => 1 });
is_deeply (csv (@parg, skip_empty_rows => 0), [ @head, $eh,
    { a => 8, b => 2, c => 7, d => 1 },$eh,$eh,
    { a => 5, b => 7, c => 9, d => 3 },$eh],			"H Default");

is_deeply (csv (@parg, skip_empty_rows => 1), [ @head,
    { a => 8, b => 2, c => 7, d => 1 },
    { a => 5, b => 7, c => 9, d => 3 }],			"H Skip");

is_deeply (csv (@parg, skip_empty_rows => 2),  \@head,		"H EOF");

is (eval { csv (@parg, skip_empty_rows => 3); }, undef,		"H die");
like ($@, qr{^Empty row},					"H msg");

is (eval { csv (@parg, skip_empty_rows => 4); }, undef,		"H croak");
like ($@, qr{^Empty row},					"H msg");

$@ = "";
$csv = Text::CSV->new ({ skip_empty_rows => 5 });
is_deeply ($csv->csv (@parg), \@head,				"H error");
is ($@, "",							"H msg");
is (0 + $csv->error_diag, 2015,					"H code");

$eh = { a => 1, b => 2, c => 3, d => 4 };
is_deeply (csv (@parg, skip_empty_rows => sub {\@repl}), [ @head, $eh,
    { a => 8, b => 2, c => 7, d => 1 },$eh,$eh,
    { a => 5, b => 7, c => 9, d => 3 },$eh],			"H Callback");

is_deeply (csv (@parg, skip_empty_rows => sub {0}), \@head,	"H Callback 0");

# Hash behavior (line by line)
open $fh, "<", $tfn;
$csv = Text::CSV->new ({ skip_empty_rows => 1 });
my $cols = $csv->getline ($fh);
$csv->column_names (@$cols);
while (my $row = $csv->getline_hr ($fh)) {
    isnt ($row->{a}, undef, "Column 'a' is defined");
    }
close $fh;
