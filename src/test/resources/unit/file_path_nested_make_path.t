use strict;
use warnings;

use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use Test::More tests => 3;

my $root = tempdir(CLEANUP => 1);
my $nested = File::Spec->catdir($root, qw(alpha beta gamma));
my @created = make_path($nested);

ok(-d $nested, 'make_path creates every level of a nested native path');
ok(@created >= 3, 'make_path reports the newly created directory levels');
is(make_path($nested), 0, 'make_path is idempotent for an existing path');
