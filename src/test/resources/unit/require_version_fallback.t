#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;
use File::Temp qw(tempdir);
use File::Spec;

# Regression: .pm files without trailing 1; succeed when $VERSION is defined.

my $dir = tempdir(CLEANUP => 1);
my $path = File::Spec->catfile($dir, 'Fallback', 'Mod.pm');
require File::Basename;
my $parent = File::Basename::dirname($path);
require File::Path;
File::Path::make_path($parent);

open my $fh, '>', $path or die $!;
print {$fh} <<'PM';
package Fallback::Mod;
our $VERSION = '9.87';
use strict;
sub foo {}
PM
close $fh;

unshift @INC, $dir;
my $ret = eval { require Fallback::Mod; 1 };
is($ret, 1, 'require succeeds via $VERSION fallback');
is($Fallback::Mod::VERSION, '9.87', 'package version preserved');

done_testing();
