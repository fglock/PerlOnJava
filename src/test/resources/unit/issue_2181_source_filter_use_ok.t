#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 2;

BEGIN {
    package Local::RejectSyntheticEval;
    use Filter::Simple;
    sub filter_package_helper { 1 }
    FILTER {
        die "synthetic eval suffix reached source filter\n" if /\A1;\n?\z/;
        die "filter callback lost its installing package\n"
            unless filter_package_helper();
        s/USE_OK_FILTER_MARKER/pass/;
    };
    $INC{'Local/RejectSyntheticEval.pm'} = __FILE__;
    Test::More::use_ok('Local::RejectSyntheticEval');
}

USE_OK_FILTER_MARKER;
