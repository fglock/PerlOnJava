#!perl -T
use strict;
use warnings;
use Cwd ();
use Scalar::Util qw(tainted);
use Test::More;

# The current working directory is operating-system data, so standard Perl
# taints every Cwd entry point under -T.  Verified against system perl before
# being used to drive the PerlOnJava fix (GitHub issue #1125).
for my $name (qw(getcwd cwd fastcwd fastgetcwd)) {
    my $code = Cwd->can($name);
    ok($code, "Cwd::$name is available");
    my $value = $code->();
    ok(defined $value && length $value, "Cwd::$name returns a path");
    ok(tainted($value), "Cwd::$name is tainted under -T");
}

for my $name (qw(abs_path realpath fast_abs_path fast_realpath)) {
    my $code = Cwd->can($name);
    ok($code, "Cwd::$name is available");
    my $value = $code->('.');
    ok(defined $value && length $value, "Cwd::$name('.') returns a path");
    ok(tainted($value), "Cwd::$name('.') is tainted under -T");
}

# A clean literal argument does not produce a clean result: abs_path resolves
# the path against the file system, so the answer is OS-derived either way.
ok(tainted(Cwd::abs_path('/')), "abs_path('/') is tainted for a clean argument");

# Data::Compare guards plugin discovery with
# "register_plugins() unless tainted(getcwd()) || !chdir $cwd", so copies and
# derived strings must keep the taint too.
my $copy = Cwd::getcwd();
ok(tainted($copy), 'a copy of getcwd() stays tainted');
ok(tainted("$copy/sub"), 'interpolating getcwd() propagates taint');

# The tainted directory cannot reach chdir without being laundered first.
my $chdir_ok = eval { chdir Cwd::getcwd(); 1 };
ok(!$chdir_ok, 'chdir rejects the tainted getcwd() value');
like($@, qr/^Insecure dependency in chdir while running with -T switch/,
    'chdir reports the Perl security error for a tainted directory');

# Laundering through a regexp match restores a usable value.
my ($laundered) = Cwd::getcwd() =~ /\A(.*)\z/s;
ok(!tainted($laundered), 'a laundered copy of getcwd() is clean');
ok(chdir($laundered), 'the laundered directory can be used with chdir');

done_testing;
