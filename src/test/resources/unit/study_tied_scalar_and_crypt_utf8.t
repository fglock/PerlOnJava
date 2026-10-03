#!/usr/bin/env perl

use strict;
use warnings;
use Test::More tests => 2;

{
    package StudyTieRegression;
    sub TIESCALAR { bless {}, $_[0] }
    sub FETCH { ++$main::study_tie_fetches == 1 ? 'first' : 'next' }
}

our $study_tie_fetches = 0;
tie my $value, 'StudyTieRegression';
study $value;
is($value, 'next', 'study fetches a tied scalar before later reads');

my $target = chr 256;
$target = crypt 'foo', 'bar';
ok(!utf8::is_utf8($target), 'crypt assignment clears the target UTF-8 flag');
