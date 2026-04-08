#!/usr/bin/perl

use strict;
$^W = 1;    # use warnings core since 5.6

use Test::More tests => 4;

BEGIN {
    $ENV{PERL_TEXT_CSV} = $ENV{TEST_PERL_TEXT_CSV} || 0;
    use_ok "Text::CSV";
    plan skip_all => "Cannot load Text::CSV" if $@;
    }

if (!$ENV{PERL_TEXT_CSV} or $ENV{PERL_TEXT_CSV} eq 'Text::CSV_PP' or !eval { require Text::CSV_XS; 1 }) {
    ok my $csv = Text::CSV->new;
    ok $csv->is_pp;
    is $csv->module => 'Text::CSV_PP';
} else {
    ok my $csv = Text::CSV->new;
    ok $csv->is_xs;
    is $csv->module => 'Text::CSV_XS';
}
