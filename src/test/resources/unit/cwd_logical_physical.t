use strict;
use warnings;
use Test::More;
use Cwd ();
use File::Temp qw(tempdir);

# Standard Perl's Cwd distinguishes the LOGICAL current directory from the
# PHYSICAL one on Unix-like systems:
#
#   cwd()        logical  - `pwd`, which reports a trusted $ENV{PWD}
#   fastgetcwd() logical  - a synonym for cwd()
#   getcwd()     physical - getcwd(3), symlinks resolved
#   fastcwd()    physical - walks up with chdir('..')
#
# PerlOnJava used to alias all four to one physical builtin.  Verified against
# system perl v5.42 on darwin before being used to drive the fix.
#
# On the platforms where standard Perl aliases all four names to a single
# platform function there is nothing to distinguish, so only run the split
# assertions elsewhere.
my $HAS_SPLIT = $^O !~ /\A(?:MSWin32|NT|dos|os2|VMS|qnx|cygwin|amigaos)\z/;

my $tmp = tempdir(CLEANUP => 1);

# Build our own symlink instead of relying on the test runner's cwd (or on
# /tmp happening to be a symlink, which is true on darwin but not on Linux).
my $real = "$tmp/real";
my $link = "$tmp/link";
mkdir $real or die "mkdir $real: $!";
if (!symlink($real, $link)) {
    plan skip_all => "symlinks unavailable: $!";
}

my $physical = Cwd::abs_path($real);
plan skip_all => "cannot resolve $real" unless defined $physical && length $physical;

# abs_path() of the symlink must agree: this is the physical answer.
is(Cwd::abs_path($link), $physical, 'abs_path resolves the symlink');

my $origin = Cwd::getcwd();
chdir $link or plan skip_all => "cannot chdir to $link: $!";

# getcwd()/fastcwd() are physical no matter what the environment claims.
{
    local $ENV{PWD} = $link;
    is(Cwd::getcwd(), $physical, 'getcwd() is physical');
    is(Cwd::fastcwd(), $physical, 'fastcwd() is physical');

    if ($HAS_SPLIT) {
        is(Cwd::cwd(), $link, 'cwd() honours a valid $ENV{PWD} (logical)');
        is(Cwd::fastgetcwd(), $link, 'fastgetcwd() honours a valid $ENV{PWD}');
    }
    else {
        is(Cwd::cwd(), $physical, 'cwd() is physical on this platform');
        is(Cwd::fastgetcwd(), $physical, 'fastgetcwd() is physical on this platform');
    }
}

# A $ENV{PWD} that does not name the current directory must never be trusted:
# cwd() falls back to the physical path.  This is what keeps a stale value left
# behind by a plain chdir, or a hostile one, from making cwd() lie.
my %untrusted = (
    'a stale but existing directory' => $tmp,
    'a nonexistent path'             => "$tmp/no-such-directory",
    'a relative path'                => 'link',
    'an empty string'                => '',
);
for my $why (sort keys %untrusted) {
    local $ENV{PWD} = $untrusted{$why};
    is(Cwd::cwd(), $physical, "cwd() ignores $why");
    is(Cwd::fastgetcwd(), $physical, "fastgetcwd() ignores $why");
}

# With PWD absent there is no logical answer at all.
{
    my $saved = delete $ENV{PWD};
    is(Cwd::cwd(), $physical, 'cwd() falls back to the physical path with no $ENV{PWD}');
    is(Cwd::fastgetcwd(), $physical, 'fastgetcwd() falls back with no $ENV{PWD}');
    $ENV{PWD} = $saved if defined $saved;
}

# Any symlinked alias of the current directory is a legitimate logical answer,
# because the trust test compares the directories themselves (device+inode),
# not the spelling of the path.
if ($HAS_SPLIT) {
    my $alias = "$tmp/alias";
    if (symlink($real, $alias)) {
        local $ENV{PWD} = $alias;
        is(Cwd::cwd(), $alias, 'cwd() accepts a different symlink to the same directory');
    }
}

chdir $origin or die "cannot chdir back to $origin: $!";

done_testing;
