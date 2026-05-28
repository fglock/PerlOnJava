#!/usr/bin/env perl
use strict;
use warnings;

use Test::More tests => 2;

pass 'emitted a test before fork';

my $child = fork();
ok !defined($child), 'operator-form fork() compiles and returns undef after tests begin';
