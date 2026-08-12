use strict;
use warnings;
use Test::More tests => 3;

use File::Glob qw(:bsd_glob);

ok(exists $File::Glob::EXPORT_TAGS{bsd_glob},
    'File::Glob provides the standard :bsd_glob export tag');
ok(defined &bsd_glob, ':bsd_glob exports bsd_glob');
is_deeply([bsd_glob('literal path')], ['literal path'],
    'imported bsd_glob is callable');
