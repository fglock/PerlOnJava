use strict;
use warnings;
use feature 'say';
use Cwd qw(getcwd cwd abs_path realpath);

# Test getcwd function
my $cwd = getcwd();
print "not " if !defined $cwd || $cwd eq ''; say "ok # getcwd returns a non-empty string";

# Test cwd function (alias for getcwd)
$cwd = cwd();
print "not " if !defined $cwd || $cwd eq ''; say "ok # cwd returns a non-empty string";

# Test abs_path function
my $abs_path = abs_path('.');
print "not " if !defined $abs_path || $abs_path eq ''; say "ok # abs_path returns a non-empty string for current directory";

# Test realpath function (alias for abs_path)
$abs_path = realpath('.');
print "not " if !defined $abs_path || $abs_path eq ''; say "ok # realpath returns a non-empty string for current directory";

# Test that getcwd and cwd return the same result
my $cwd1 = getcwd();
my $cwd2 = cwd();
print "not " if $cwd1 ne $cwd2; say "ok # getcwd and cwd return the same result";

# Test that abs_path and realpath return the same result
my $abs_path1 = abs_path('.');
my $abs_path2 = realpath('.');
print "not " if $abs_path1 ne $abs_path2; say "ok # abs_path and realpath return the same result for current directory";

