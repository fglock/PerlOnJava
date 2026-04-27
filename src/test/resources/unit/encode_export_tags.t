#!/usr/bin/perl
use strict;
use warnings;
use Test::More tests => 8;

# Regression test for Encode %EXPORT_TAGS parity with core Encode.pm.
# Previously PerlOnJava only registered :fallbacks and :fallback_all,
# so any module doing `use Encode qw(:all)` or qw(:default) died with
# `"all" is not defined in %Encode::EXPORT_TAGS`.

use Encode ();

ok(exists $Encode::EXPORT_TAGS{all},          'Encode :all tag exists');
ok(exists $Encode::EXPORT_TAGS{default},      'Encode :default tag exists');
ok(exists $Encode::EXPORT_TAGS{fallbacks},    'Encode :fallbacks tag exists');
ok(exists $Encode::EXPORT_TAGS{fallback_all}, 'Encode :fallback_all tag exists');

# :default should mirror @EXPORT
my %default = map { $_ => 1 } @{ $Encode::EXPORT_TAGS{default} };
ok($default{encode} && $default{decode}, ':default contains encode and decode');

# :all should be a superset of :default
my %all = map { $_ => 1 } @{ $Encode::EXPORT_TAGS{all} };
ok($all{encode} && $all{decode}, ':all contains :default symbols');
ok($all{FB_CROAK}, ':all contains EXPORT_OK symbols');

# Importing :all and :default must not die
eval "use Encode qw(:all); 1"     or die $@;
eval "use Encode qw(:default); 1" or die $@;
ok(1, 'use Encode qw(:all) and qw(:default) succeed');
