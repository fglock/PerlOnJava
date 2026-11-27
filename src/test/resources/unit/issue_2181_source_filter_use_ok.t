#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 2;

BEGIN {
    package Local::RejectSyntheticEval;
    use Filter::Util::Call;
    sub filter_package_helper {
        my $target = 'filter_package_leaf';
        no strict 'refs';
        return "$target"->();
    }
    sub filter_package_leaf { 'pass' }
    sub import {
        filter_add(sub {
            my $status = filter_read();
            die "synthetic eval suffix reached source filter\n" if /\A1;\n?\z/;
            s/USE_OK_FILTER_MARKER/filter_package_helper()/e;
            return $status;
        });
    }
    $INC{'Local/RejectSyntheticEval.pm'} = __FILE__;
    Test::More::use_ok('Local::RejectSyntheticEval');
}

USE_OK_FILTER_MARKER;
