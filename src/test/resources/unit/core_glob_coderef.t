use strict;
use warnings;
use Test::More tests => 2;

my $glob = \&CORE::glob;
my @files = $glob->('README.md');
is_deeply(\@files, ['README.md'],
    'CORE::glob code reference evaluates a pattern in list context');

my $file = $glob->('README.md');
is($file, 'README.md',
    'CORE::glob code reference returns a match in scalar context');
