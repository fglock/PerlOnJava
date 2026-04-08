#!/usr/bin/perl

use strict;
$^W = 1;

use Test::More;

print STDERR "# PERL_TEXT_CSV: ", (defined $ENV{TEST_PERL_TEXT_CSV} ? "$ENV{TEST_PERL_TEXT_CSV}" : "undef"), "\n";

eval "use Test::Pod 1.00";
plan skip_all => "Test::Pod 1.00 required for testing POD" if $@;
all_pod_files_ok ();
