#!/usr/bin/env perl

use strict;
use warnings;

use Test::More tests => 6;
use version ();

# Matches CPANPLUS::Internals::Utils::_version_to_number + core version.pm tuples/decimals.

is(version->parse('v1.5')->numify,       '1.005000');
is(version->parse('1.5')->numify,       '1.500');
is(version->parse('v1')->numify,        '1.000000');
is(version->parse('v1.234.5')->numify, '1.234005');
is(version->parse('2')->numify,        '2.000');
is(version->parse('1.2345')->numify,   '1.234500');
