use strict;
use warnings;
use Config;
use Fcntl qw(O_CREAT O_RDWR);
use File::Spec;
use File::Temp qw(tempdir);
use Test::More;

my $is_perlonjava = $Config::Config{archname} =~ /^java-/;
if ($is_perlonjava) {
    require SDBM_File;
} else {
    require './src/main/perl/lib/SDBM_File.pm';
}

my $root = tempdir(CLEANUP => 1);
my $file = File::Spec->catfile($root, 'cache');

my %cache;
ok(tie(%cache, 'SDBM_File', $file, O_CREAT | O_RDWR, 0600),
   'SDBM file can be tied');
$cache{one} = 'first';
$cache{two} = 'second';
untie %cache;

ok(tie(%cache, 'SDBM_File', $file, O_CREAT | O_RDWR, 0600),
   'SDBM file can be reopened');
is($cache{one}, 'first', 'deferred writeback persists values on untie');
is($cache{two}, 'second', 'all deferred values are persisted');
untie %cache;

SKIP: {
    skip 'POSIX permission behavior is unavailable on this platform', 1
        if $^O eq 'MSWin32' || $> == 0;
    chmod 0, $file or skip 'cannot remove test-file permissions', 1;
    ok(!tie(%cache, 'SDBM_File', $file, O_CREAT | O_RDWR, 0600),
       'TIEHASH reports an inaccessible backing file');
}

done_testing;
