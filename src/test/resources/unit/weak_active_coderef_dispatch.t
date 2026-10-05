#!/usr/bin/env perl
use strict;
use warnings;

use Scalar::Util qw(weaken);
use Test::More tests => 3;

my ($strong, $weak);
my $called = 0;

$strong = sub {
    ++$called;
    undef $strong;
    ok(defined($weak), 'active callback stays alive after its last lexical owner is released');
};
$weak = $strong;
weaken($weak);

$strong->();

is($called, 1, 'callback dispatch completes after releasing its lexical owner');
ok(!defined($weak), 'weak callback reference clears after dispatch returns');
