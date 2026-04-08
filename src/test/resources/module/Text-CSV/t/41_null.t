#!/usr/bin/perl

use strict;
$^W = 1;

use Test::More tests => 128;
BEGIN { $ENV{PERL_TEXT_CSV} = $ENV{TEST_PERL_TEXT_CSV} || 0; }
use Text::CSV;

my @pat = (
    "00", 
    "\00",
    "0\0",
    "\0\0",

    "0\n0",
    "\0\n0",
    "0\n\0",
    "\0\n\0",

    "\"0\n0",
    "\"\0\n0",
    "\"0\n\0",
    "\"\0\n\0",

    "\"0\n\"0",
    "\"\0\n\"0",
    "\"0\n\"\0",
    "\"\0\n\"\0",

    "0\n0",
    "\0\n0",
    "0\n\0",
    "\0\n\0",
    );
my %exp = map {
    my $x = $_;
    $x =~ s/\0/\\0/g;
    $x =~ s/\n/\\n/g;
    ($_ => $x);
    } @pat;
my $line = ["", undef, "0\n", "", "\0\0\n0"];
my $tfn = "_41test.csv"; END { -f $tfn and unlink $tfn; }

my $csv = Text::CSV->new ({
    eol			=> "\n",
    binary		=> 1,
    auto_diag		=> 1,
    blank_is_undef	=> 1,
    });

ok ($csv->combine (@$line), "combine [ ... ]");
is ($csv->string, qq{,,"0\n",,""0"0\n0"\n}, "string");

open my $fh, ">", $tfn or die "$tfn: $!\n";
binmode $fh;

ok ($csv->print ($fh, [ $_ ]), "print $exp{$_}") for @pat;

$csv->always_quote (1);

ok ($csv->print ($fh, $line), "print [ ... ]");

close $fh;

open $fh, "<", $tfn or die "$tfn: $!\n";
binmode $fh;

foreach my $pat (@pat) {
    ok (my $row = $csv->getline ($fh), "getline $exp{$pat}");
    is ($row->[0], $pat, "data $exp{$pat}");
    }

is_deeply ($csv->getline ($fh), $line, "read [ ... ]");

close  $fh;
unlink $tfn;

$csv = Text::CSV->new ({
    eol			=> "\n",
    binary		=> 1,
    auto_diag		=> 1,
    blank_is_undef	=> 1,
    quote_null		=> 0,
    });

ok ($csv->combine (@$line), "combine [ ... ]");
is ($csv->string, qq{,,"0\n",,"\0\0\n0"\n}, "string");

open $fh, ">", $tfn or die "$tfn: $!\n";
binmode $fh;

for (@pat) {
    ok ($csv->print ($fh, [ $_ ]), "print $exp{$_}");
    }

$csv->always_quote (1);

ok ($csv->print ($fh, $line), "print [ ... ]");

close $fh;

open $fh, "<", $tfn or die "$tfn: $!\n";
binmode $fh;

foreach my $pat (@pat) {
    ok (my $row = $csv->getline ($fh), "getline $exp{$pat}");
    is ($row->[0], $pat, "data $exp{$pat}");
    }

is_deeply ($csv->getline ($fh), $line, "read [ ... ]");

close $fh;
