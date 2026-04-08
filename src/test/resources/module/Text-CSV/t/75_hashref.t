#!/usr/bin/perl

use strict;
$^W = 1;

#use Test::More "no_plan";
 use Test::More tests => 102;

BEGIN {
    $ENV{PERL_TEXT_CSV} = $ENV{TEST_PERL_TEXT_CSV} || 0;
    use_ok "Text::CSV", ();
    plan skip_all => "Cannot load Text::CSV" if $@;
    }

my $tfn = "_75hashref.csv"; END { -f $tfn and unlink $tfn; }

open my $fh, ">", $tfn or die "$tfn: $!\n";
print $fh <<EOC;
code,name,price,description
1,Dress,240.00,"Evening gown"
2,Drinks,82.78,"Drinks"
3,Sex,-9999.99,"Priceless"
4,Hackathon,0,"QA Hackathon Oslo 2008"
EOC
close $fh;

ok (my $csv = Text::CSV->new (),	"new");
is ($csv->column_names, undef,		"No headers yet");

foreach my $args ([\1], ["foo", \1], [{ 1 => 2 }]) {
    eval { $csv->column_names (@$args) };
    like ($@, qr/^EHR/, "croak");
    is ($csv->error_diag () + 0, 3001,	"Bad args to column_names");
    }

ok ($csv->column_names ("name"),	"One single name");
is ($csv->column_names (undef), undef,	"reset column_names");
eval { $csv->column_names (\undef) };
is ($csv->error_diag () + 0, 3001, "No hash please");
eval { $csv->column_names ({ 1 => 2 }) };
is ($csv->error_diag () + 0, 3001, "No hash please");

my $hr;
eval { $hr = $csv->getline_hr ($fh) };
is ($hr, undef,	"getline_hr before column_names");
like ($@, qr/^EHR/, "croak");
is ($csv->error_diag () + 0, 3002, "error code");

ok ($csv->column_names ("name", "code"), "column_names (list)");
is_deeply ([ $csv->column_names ], [ "name", "code" ], "well set");

open $fh, "<", $tfn or die "$tfn: $!\n";
my $row;
ok ($row = $csv->getline ($fh),		"getline headers");
is ($row->[0], "code",			"Header line");
ok ($csv->column_names ($row),		"column_names from array_ref");
is_deeply ([ $csv->column_names ], [ @$row ], "Keys set");
while (my $hr = $csv->getline_hr ($fh)) {
    ok (exists $hr->{code},			"Line has a code field");
    like ($hr->{code}, qr/^[0-9]+$/,		"Code is numeric");
    ok (exists $hr->{name},			"Line has a name field");
    like ($hr->{name}, qr/^[A-Z][a-z]+$/,	"Name");
    }
close $fh;

my ($code, $name, $price, $desc) = (1..4);
is ($csv->bind_columns (), undef,		"No bound columns yet");
eval { $csv->bind_columns (\$code) };
is ($csv->error_diag () + 0, 3003,		"Arg cound mismatch");
eval { $csv->bind_columns ({}, {}, {}, {}) };
is ($csv->error_diag () + 0, 3004,		"bad arg types");
is ($csv->column_names (undef), undef,		"reset column_names");
ok ($csv->bind_columns (\($code, $name, $price)), "Bind columns");

eval { $csv->column_names ("foo") };
is ($csv->error_diag () + 0, 3003,		"Arg cound mismatch");
$csv->bind_columns (undef);
eval { $csv->bind_columns ([undef]) };
is ($csv->error_diag () + 0, 3004,		"legal header defenition");

my @bcr = \($code, $name, $price, $desc);
open $fh, "<", $tfn or die "$tfn: $!\n";
ok ($row = $csv->getline ($fh),			"getline headers");
ok ($csv->bind_columns (@bcr),			"Bind columns");
ok ($csv->column_names ($row),			"column_names from array_ref");
is_deeply ([ $csv->column_names ], [ @$row ],	"Keys set");

$row = $csv->getline ($fh);
is_deeply ([ $csv->bind_columns ], [ @bcr ],	"check refs");
is_deeply ($row, [],		"return from getline with bind_columns");

is ($csv->column_names (undef), undef,		"reset column headers");
is ($csv->bind_columns (undef), undef,		"reset bound columns");

my $foo;
ok ($csv->bind_columns (@bcr, \$foo),		"bind too many columns");
($code, $name, $price, $desc, $foo) = (101 .. 105);
ok ($csv->getline ($fh),			"fetch less than expected");
is_deeply ([ $code, $name, $price, $desc, $foo ],
	   [ 2, "Drinks", "82.78", "Drinks", 105 ],	"unfetched not reset");

my @foo = (0) x 0x012345;
ok ($csv->bind_columns (\(@foo)),		"bind a lot of columns");

ok ($csv->bind_columns (\1, \2, \3, \""),	"bind too constant columns");
is ($csv->getline ($fh), undef,			"fetch to read-only ref");
is ($csv->error_diag () + 0, 3008,		"Read-only");

ok ($csv->bind_columns (\$code),		"bind not enough columns");
eval { $row = $csv->getline ($fh) };
is ($csv->error_diag () + 0, 3006,		"cannot read all fields");

close $fh;

open $fh, "<", $tfn or die "$tfn: $!\n";

is ($csv->column_names (undef), undef,		"reset column headers");
is ($csv->bind_columns (undef), undef,		"reset bound columns");
is_deeply ([ $csv->column_names (undef, "", "name", "name") ],
	   [ "\cAUNDEF\cA", "", "name", "name" ],	"undefined column header");
ok ($hr = $csv->getline_hr ($fh),		"getline_hr ()");
is (ref $hr, "HASH",				"returned a hashref");
is_deeply ($hr, { "\cAUNDEF\cA" => "code", "" => "name", "name" => "description" },
    "Discarded 3rd field");

close $fh;

open $fh, ">", $tfn or die "$tfn: $!\n";
$hr = { c_foo => 1, foo => "poison", zebra => "Of course" };
is ($csv->column_names (undef), undef,		"reset column headers");
ok ($csv->column_names (sort keys %$hr),	"set column names");
ok ($csv->eol ("\n"),				"set eol for output");
ok ($csv->print ($fh, [ $csv->column_names ]),	"print header");
ok ($csv->print_hr ($fh, $hr),			"print_hr");
ok ($csv->print ($fh, []),			"empty print");
close $fh;
ok ($csv->keep_meta_info (1),			"keep meta info");
open $fh, "<", $tfn or die "$tfn: $!\n";
ok ($csv->column_names ($csv->getline ($fh)),	"get column names");
is_deeply ($csv->getline_hr ($fh), $hr,		"compare to written hr");

is_deeply ($csv->getline_hr ($fh),
    { c_foo => "", foo => undef, zebra => undef },	"compare to written hr");
is ($csv->is_missing (1), 1,			"No col 1");
close $fh;

open $fh, ">", $tfn or die "$tfn: $!\n";
print $fh <<"EOC";
a,b

2
EOC
close $fh;

ok ($csv = Text::CSV->new (), "new");

open $fh, "<", $tfn or die "$tfn: $!\n";
ok ($csv->column_names ("code", "foo"), "set column names");
ok ($hr = $csv->getline_hr ($fh), "get header line");
is ($csv->is_missing (0), undef, "not is_missing () - no meta");
is ($csv->is_missing (1), undef, "not is_missing () - no meta");
ok ($hr = $csv->getline_hr ($fh), "get empty line");
is ($csv->is_missing (0), undef, "not is_missing () - no meta");
is ($csv->is_missing (1), undef, "not is_missing () - no meta");
ok ($hr = $csv->getline_hr ($fh), "get partial data line");
is (int $hr->{code}, 2, "code == 2");
is ($csv->is_missing (0), undef, "not is_missing () - no meta");
is ($csv->is_missing (1), undef, "not is_missing () - no meta");
close $fh;

open $fh, "<", $tfn or die "$tfn: $!\n";
$csv->keep_meta_info (1);
ok ($csv->column_names ("code", "foo"), "set column names");
ok ($hr = $csv->getline_hr ($fh), "get header line");
is ($csv->is_missing (0), 0, "not is_missing () - with meta");
is ($csv->is_missing (1), 0, "not is_missing () - with meta");
ok ($hr = $csv->getline_hr ($fh), "get empty line");
is ($csv->is_missing (0), 1, "not is_missing () - with meta");
is ($csv->is_missing (1), 1, "not is_missing () - with meta");
ok ($hr = $csv->getline_hr ($fh), "get partial data line");
is (int $hr->{code}, 2, "code == 2");
is ($csv->is_missing (0), 0, "not is_missing () - with meta");
is ($csv->is_missing (1), 1, "not is_missing () - with meta");
close $fh;
