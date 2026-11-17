use strict;
use warnings;
use Test::More;
use Cwd qw(realpath);
use File::Spec;
use File::Temp qw(tempdir);

my $parent = tempdir(CLEANUP => 1);
my $expected_parent = realpath($parent);
my $missing_leaf = File::Spec->catfile($parent, 'missing-leaf');
is realpath($missing_leaf),
    File::Spec->catfile($expected_parent, 'missing-leaf'),
    'realpath resolves an existing parent and preserves a missing leaf';
ok !defined realpath(File::Spec->catfile($parent, 'missing-parent', 'leaf')),
    'realpath returns undef when a parent component is missing';

my $link = File::Spec->catfile($parent, 'parent-link');
SKIP: {
    skip 'symlink creation is unavailable', 1 unless symlink($parent, $link);
    is realpath(File::Spec->catfile($link, 'missing-leaf')),
        File::Spec->catfile($expected_parent, 'missing-leaf'),
        'realpath canonicalizes a symlinked parent before appending a missing leaf';
}

done_testing;
