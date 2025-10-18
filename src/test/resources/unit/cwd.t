use strict;
use warnings;
use Test::More;
use feature 'say';
use Cwd qw(getcwd cwd abs_path realpath);

# Test getcwd function
my $cwd = getcwd();
ok(defined $cwd && $cwd ne '', 'getcwd returns a non-empty string');

# Test cwd function (alias for getcwd)
$cwd = cwd();
ok(defined $cwd && $cwd ne '', 'cwd returns a non-empty string');

# Test abs_path function
my $abs_path = abs_path('.');
ok(defined $abs_path && $abs_path ne '', 'abs_path returns a non-empty string for current directory');

# Test realpath function (alias for abs_path)
$abs_path = realpath('.');
ok(defined $abs_path && $abs_path ne '', 'realpath returns a non-empty string for current directory');

# Test that getcwd and cwd return the same result
my $cwd1 = getcwd();
my $cwd2 = cwd();
is($cwd1, $cwd2, 'getcwd and cwd return the same result');

# Test that abs_path and realpath return the same result
my $abs_path1 = abs_path('.');
my $abs_path2 = realpath('.');
is($abs_path1, $abs_path2, 'abs_path and realpath return the same result for current directory');

done_testing();
