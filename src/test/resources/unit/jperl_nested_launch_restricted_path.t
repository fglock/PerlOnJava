use strict;
use warnings;

use Config;
use File::Spec;
use Test::More;

plan skip_all => 'nested launcher checks run only under PerlOnJava'
    unless $ENV{PERLONJAVA_EXECUTABLE}
        && -x $ENV{PERLONJAVA_EXECUTABLE};

plan skip_all => 'restricted PATH launcher check is Unix-only'
    if $^O eq 'MSWin32';

my @path = split /\Q$Config{path_sep}\E/, $ENV{PATH} // '';
my ($timeout) = grep { -x $_ }
    map { File::Spec->catfile($_, 'timeout') } @path;
plan skip_all => 'timeout utility is unavailable' unless $timeout;
my $java = $ENV{PERLONJAVA_JAVA_BIN};
($java) = grep { -x $_ }
    map { File::Spec->catfile($_, 'java') } @path
    unless defined($java) && -x $java;
plan skip_all => 'java executable is unavailable' unless $java;

my $jperl = $ENV{PERLONJAVA_EXECUTABLE};
my $status;
{
    # Model the Java binding exported by the outer launcher. A child jperl
    # must reuse that absolute path when PATH drops java and normal utilities,
    # as local::lib's shell.t does.
    local $ENV{PERLONJAVA_JAVA_BIN} = $java;
    local $ENV{PATH} = File::Spec->rootdir;
    $status = system($timeout, '30', $jperl, '-e', 'exit 0');
}

is($status, 0, 'nested jperl starts and exits with a restricted PATH');

done_testing;
