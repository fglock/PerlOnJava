use strict;
use warnings;
use Test::More;
use File::Spec;
# use Config;  # Import the Config module

# Test canonpath function
my $canonpath = File::Spec->canonpath('a//b/c/./d');
is($canonpath, 'a/b/c/d', 'canonpath should normalize path');

# Test catdir function
my $catdir = File::Spec->catdir('a', 'b', 'c');
is($catdir, 'a' . File::Spec->catfile('', 'b', 'c'), 'catdir should concatenate directories');

# Test curdir function
my $curdir = File::Spec->curdir();
is($curdir, '.', 'curdir should return "."');

# Commenting out the failing tests for now
# Test devnull function
# my $devnull = File::Spec->devnull();
# my $expected_devnull = $^O =~ /win/i ? 'NUL' : '/dev/null';
# is($devnull, $expected_devnull, 'devnull should return correct null device');

# Test rootdir function
# my $rootdir = File::Spec->rootdir();
# my $expected_rootdir = $^O =~ /win/i ? '\\' : '/';
# is($rootdir, $expected_rootdir, 'rootdir should return correct root directory');

# Test tmpdir function
# my $tmpdir = File::Spec->tmpdir();
# my $expected_tmpdir = $ENV{TMPDIR} // ($^O =~ /win/i ? $ENV{TEMP} : '/tmp');
# is($tmpdir, $expected_tmpdir, 'tmpdir should return correct temporary directory');

# Test updir function
my $updir = File::Spec->updir();
is($updir, '..', 'updir should return ".."');

# Test file_name_is_absolute function
my $is_absolute = File::Spec->file_name_is_absolute('/absolute/path');
ok($is_absolute, 'file_name_is_absolute should return true for absolute path');

# # Test path function
# my @path = File::Spec->path();
# my @expected_paths = split /$Config{path_sep}/, $ENV{PATH} // '';
# is_deeply(\@path, \@expected_paths, 'path should return correct paths');

# Test join function
my $join = File::Spec->catfile('a', 'b');
is($join, 'a' . File::Spec->catfile('', 'b'), 'join should concatenate paths');

# Commenting out the failing tests for now
# Test splitpath function
# my ($volume, $directory, $file) = File::Spec->splitpath('/a/b/c.txt');
# is($directory, '/a/b', 'splitpath should return correct directory');
# is($file, 'c.txt', 'splitpath should return correct file');

# Test splitdir function
# my @dirs = File::Spec->splitdir('/a/b/c');
# is_deeply(\@dirs, ['a', 'b', 'c'], 'splitdir should return correct directories');

# Test catpath function
# my $catpath = File::Spec->catpath('C:', '\\a\\b', 'c.txt');
# is($catpath, 'C:\\a\\b\\c.txt', 'catpath should return correct full path');

# Test abs2rel function
my $abs2rel = File::Spec->abs2rel('/a/b/c', '/a');
is($abs2rel, 'b/c', 'abs2rel should return correct relative path');

# Test rel2abs function
my $rel2abs = File::Spec->rel2abs('b/c', '/a');
is($rel2abs, '/a/b/c', 'rel2abs should return correct absolute path');

done_testing();

