#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);

my ($raw_fh, $filename) = tempfile();
close $raw_fh;

open my $encoded_fh, '>:encoding(UTF-8)', $filename
    or die "open with encoding layer: $!";

ok(defined(&Encode::encode),
    ':encoding(...) loads Encode functions');

close $encoded_fh;
unlink $filename;

done_testing;
