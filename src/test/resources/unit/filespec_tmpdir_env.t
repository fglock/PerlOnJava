use strict;
use warnings;
use Test::More tests => 2;
use File::Spec;
use File::Temp qw(tempdir);

my $dir = tempdir(CLEANUP => 1);
local $ENV{TMPDIR} = $dir;

is(File::Spec->tmpdir, $dir, 'File::Spec->tmpdir honors current TMPDIR');

local $ENV{TMPDIR} = "$dir/missing";
isnt(File::Spec->tmpdir, $ENV{TMPDIR}, 'File::Spec->tmpdir ignores missing TMPDIR');
