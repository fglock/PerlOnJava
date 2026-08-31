#!perl -T
use strict;
use warnings;
use Cwd ();
use Scalar::Util qw(tainted);
use Test::More tests => 6;

# chdir(DIRHANDLE) uses fchdir(2), which takes a descriptor and not a pathname,
# so standard Perl has nothing to taint-check: the call succeeds under -T even
# when the directory was opened from a tainted path.  PerlOnJava implements the
# handle form by recovering the pathname behind the descriptor, and that
# recovered pathname must not be pushed back through the taint check (GitHub
# issue #1187).  The pathname form of chdir stays taint-checked.

my $cwd = Cwd::getcwd();
ok(tainted($cwd), 'getcwd() is tainted under -T');

opendir(my $dh, $cwd) or die "opendir: $!";
my $ok = eval { chdir($dh) };
ok($ok, 'chdir(DIRHANDLE) succeeds under -T');
is($@, '', '...without an insecure-dependency error');
closedir($dh);

open(my $fh, '<', $cwd) or die "open: $!";
$ok = eval { chdir($fh) };
ok($ok, 'chdir(FILEHANDLE on a directory) succeeds under -T');
close $fh;

# The pathname form is still refused.
$ok = eval { chdir($cwd); 1 };
ok(!$ok, 'chdir(TAINTED PATH) is still refused under -T');
like($@, qr/^Insecure dependency in chdir while running with -T switch/,
    '...with the Perl security error');
