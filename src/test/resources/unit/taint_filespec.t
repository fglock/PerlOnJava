#!perl -T
use strict;
use warnings;
use File::Spec ();
use Scalar::Util qw(tainted);
use Test::More;

# File::Spec derives its answers from its arguments and, for the methods that
# need a base directory, from Cwd::getcwd().  The current directory is
# operating-system data, so standard Perl taints exactly those results and
# leaves the purely lexical path manipulations clean.  Every expectation below
# was recorded from system perl 5.42 under -T before being used to drive the
# PerlOnJava fix; keep them in sync with real perl, not with PerlOnJava.

# A tainted string to feed through the path-manipulation methods.
my $dirty = $ENV{PATH};
ok(tainted($dirty), '$ENV{PATH} is tainted under -T');

# A top-level name that will not collide with a real component of the
# directory the test happens to run in, so the abs2rel expectations below stay
# independent of the checkout location.
my $abs = '/zzz_taint_probe/leaf';

# ---------------------------------------------------------------------------
# rel2abs: taints exactly when the current directory has to be consulted.
# ---------------------------------------------------------------------------
ok(tainted(File::Spec->rel2abs('x')),
    "rel2abs('x') is tainted: the missing base comes from getcwd()");
ok(!tainted(File::Spec->rel2abs($abs)),
    'rel2abs() of an absolute path is clean: no base is needed');
ok(!tainted(File::Spec->rel2abs('x', '/base')),
    'rel2abs() with an absolute base is clean');
ok(!tainted(File::Spec->rel2abs($abs, '/base')),
    'rel2abs() with an absolute path and base is clean');
ok(tainted(File::Spec->rel2abs('x', 'rel_base')),
    'rel2abs() with a relative base is tainted: the base is resolved via getcwd()');
if ($^O eq 'MSWin32') {
    # Win32 consults getdcwd() for an empty base. Its path decomposition
    # launders that value, matching system Perl's File::Spec::Win32.
    ok(!tainted(File::Spec->rel2abs('x', '')),
        'Win32 rel2abs() with an empty base is clean after getdcwd() decomposition');
}
else {
    ok(tainted(File::Spec->rel2abs('x', '')),
        "rel2abs() with an empty base falls back to getcwd() and is tainted");
}
ok(tainted(File::Spec->rel2abs('.')),
    "rel2abs('.') is tainted");

# ---------------------------------------------------------------------------
# abs2rel: the getcwd()-derived components cancel out against each other, so
# only a relative *path* argument leaves operating-system data in the answer.
# ---------------------------------------------------------------------------
ok(!tainted(File::Spec->abs2rel($abs)),
    'abs2rel() of an absolute path is clean even though the base is getcwd()');
ok(tainted(File::Spec->abs2rel('x')),
    'abs2rel() of a relative path is tainted: the path is resolved via getcwd()');
ok(!tainted(File::Spec->abs2rel($abs, '/base')),
    'abs2rel() with an absolute path and base is clean');
ok(tainted(File::Spec->abs2rel('x', '/base')),
    'abs2rel() of a relative path against an absolute base is tainted');
ok(!tainted(File::Spec->abs2rel($abs, 'rel_base')),
    'abs2rel() with a relative base is clean: the base taint cancels out');
ok(!tainted(File::Spec->abs2rel('/a/b', '/a/b')),
    'abs2rel() of equal paths returns a clean curdir');

# ---------------------------------------------------------------------------
# Constants and lexical manipulations: never tainted for clean inputs.
# ---------------------------------------------------------------------------
ok(!tainted(File::Spec->curdir), 'curdir is clean');
ok(!tainted(File::Spec->updir), 'updir is clean');
ok(!tainted(File::Spec->rootdir), 'rootdir is clean');
ok(!tainted(File::Spec->devnull), 'devnull is clean');
ok(!tainted(File::Spec->case_tolerant), 'case_tolerant is clean');
ok(!tainted(File::Spec->canonpath('a/./b')), 'canonpath() of a clean path is clean');
ok(!tainted(File::Spec->canonpath('')), 'canonpath() of the empty string is clean');
ok(!tainted(File::Spec->catfile('a', 'b')), 'catfile() of clean parts is clean');
ok(!tainted(File::Spec->catdir('a', 'b')), 'catdir() of clean parts is clean');
ok(!tainted(File::Spec->catdir()), 'catdir() with no arguments is clean');
ok(!tainted(File::Spec->join('a', 'b')), 'join() of clean parts is clean');
ok(!tainted(File::Spec->catpath('', 'a/b', 'c')), 'catpath() of clean parts is clean');
ok(!tainted(File::Spec->file_name_is_absolute('/x')),
    'file_name_is_absolute() returns a clean boolean for a clean path');
ok(!tainted(File::Spec->file_name_is_absolute($dirty)),
    'file_name_is_absolute() returns a clean boolean even for a tainted path');
ok(!tainted((File::Spec->no_upwards('a', '..'))[0]), 'no_upwards() keeps clean names clean');
ok(!tainted($_), 'splitpath() of a clean path is clean') for File::Spec->splitpath('/a/b/c');
ok(!tainted($_), 'splitdir() of a clean path is clean') for File::Spec->splitdir('/a/b/c');

# tmpdir consults %ENV, but File::Spec::Unix::_tmpdir discards every tainted
# candidate under -T and falls back to the hard-coded directory, so the result
# is clean.  Over-tainting it would break callers that open temporary files.
my $tmpdir = File::Spec->tmpdir;
ok(defined $tmpdir && length $tmpdir, 'tmpdir returns a path');
ok(!tainted($tmpdir), 'tmpdir is clean under -T: tainted %ENV candidates are dropped');

# path() is the one method that hands back %ENV data verbatim.  Win32 adds a
# clean literal "." ahead of the environment-derived entries, so check its
# first PATH entry rather than assuming index zero is from %ENV.
my @path = File::Spec->path();
SKIP: {
    if ($^O eq 'MSWin32') {
        skip 'no PATH entry after Win32 curdir', 2 unless @path > 1;
        ok(!tainted($path[0]), 'Win32 path() prepends a clean curdir');
        ok(tainted($path[1]), 'Win32 path() keeps PATH-derived entries tainted');
    }
    else {
        skip 'no PATH entries to check', 1 unless @path;
        ok(tainted($path[0]), 'path() returns tainted entries: it splits $ENV{PATH}');
    }
}

# ---------------------------------------------------------------------------
# Taint propagation from the caller's arguments.
# ---------------------------------------------------------------------------
ok(tainted(File::Spec->canonpath($dirty)), 'canonpath() propagates argument taint');
ok(tainted(File::Spec->catfile($dirty, 'x')), 'catfile() propagates taint from a directory');
ok(tainted(File::Spec->catfile('x', $dirty)), 'catfile() propagates taint from the file');
ok(tainted(File::Spec->catdir($dirty, 'x')), 'catdir() propagates argument taint');
ok(tainted(File::Spec->join($dirty, 'x')), 'join() propagates argument taint');
ok(tainted(File::Spec->rel2abs($dirty, '/base')), 'rel2abs() propagates path taint');
ok(tainted(File::Spec->rel2abs('x', $dirty)), 'rel2abs() propagates base taint');
ok(tainted(File::Spec->abs2rel($dirty, '/base')), 'abs2rel() propagates path taint');
ok(tainted(File::Spec->catpath('', $dirty, 'c')), 'catpath() propagates directory taint');
ok(tainted((File::Spec->no_upwards($dirty))[0]), 'no_upwards() passes tainted names through');
ok(tainted($_), 'splitdir() propagates argument taint') for File::Spec->splitdir($dirty);

# splitpath() extracts its fields with a regexp match, and captures launder
# taint unless "use re 'taint'" is in effect, so the pieces come back clean.
ok(!tainted($_), 'splitpath() launders taint through its captures')
    for File::Spec->splitpath($dirty);

# With $no_file the directory field is the argument itself, not a capture.
{
    my ($volume, $directory, $file) = File::Spec->splitpath($dirty, 1);
    ok(!tainted($volume), 'splitpath($path, 1) returns a clean volume');
    ok(tainted($directory), 'splitpath($path, 1) hands back the tainted path as the directory');
    ok(!tainted($file), 'splitpath($path, 1) returns a clean file');
}

SKIP: {
    skip 'volume is significant outside Unix', 1 if $^O eq 'MSWin32';
    ok(!tainted(File::Spec->catpath($dirty, 'a', 'b')),
        'catpath() ignores the volume on Unix, so its taint does not reach the result');
}

# A getcwd()-derived path cannot reach an operation that touches the file
# system without being laundered first.
my $chdir_ok = eval { chdir File::Spec->rel2abs('.'); 1 };
ok(!$chdir_ok, "chdir rejects the tainted rel2abs('.') value");
like($@, qr/^Insecure dependency in chdir while running with -T switch/,
    'chdir reports the Perl security error for a tainted directory');

my ($laundered) = File::Spec->rel2abs('.') =~ /\A(.*)\z/s;
ok(!tainted($laundered), 'a laundered rel2abs() result is clean');
ok(chdir($laundered), 'the laundered directory can be used with chdir');

done_testing;
