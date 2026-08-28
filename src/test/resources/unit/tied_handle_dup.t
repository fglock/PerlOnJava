use strict;
use warnings;
no warnings 'once';    # *SAVEOUT and *SAVEOUT2 are used once, as in CPAN.pm
use Test::More tests => 8;
use File::Spec;
use File::Temp qw(tempdir);

# Duplicating a tied filehandle is valid Perl: open() dups the PerlIO stored in
# the glob's IO slot and ignores the tie magic, so the duplicate is a plain
# handle onto the same file and no tie method is called.  CPAN.pm does this
# during initialization (`my $x = *SAVEOUT; open($x, '>&STDOUT')`), which used
# to fail with EBADF once a test had tied STDOUT (GitHub issue #1165).

package Catch;

my $fileno_calls = 0;

sub TIEHANDLE { bless { got => [] }, shift }
sub PRINT     { my $self = shift; push @{ $self->{got} }, join('', @_); 1 }
sub FILENO    { $fileno_calls++; return 42 }
sub fileno_calls { $fileno_calls }

package main;

my $dir  = tempdir(CLEANUP => 1);
my $path = File::Spec->catfile($dir, 'tied.txt');

open(MYOUT, '>', $path) or die "open: $!";
my $catcher = tie *MYOUT, 'Catch' or die "tie failed: $!";

my $saved = *SAVEOUT;
ok(open($saved, '>&MYOUT'), 'dup of a tied filehandle succeeds');
is(Catch::fileno_calls(), 0, 'the dup does not call FILENO on the tied handle');
ok(!defined(tied *$saved), 'the duplicate is not itself tied');

print {$saved} "to-dup\n";
print MYOUT "to-tie\n";

close $saved;

my $captured = [ @{ $catcher->{got} } ];
undef $catcher;    # so untie does not warn about inner references
untie *MYOUT;
close MYOUT;

open(my $in, '<', $path) or die "open: $!";
my @lines = <$in>;
close $in;

is_deeply(\@lines, ["to-dup\n"], 'writes to the duplicate reach the real file');
is_deeply($captured, ["to-tie\n"], 'writes to the tied handle reach PRINT');

# A tied glob that never had a real handle has nothing to duplicate.
{
    package Catch2;
    sub TIEHANDLE { bless {}, shift }
    sub PRINT     { 1 }
}
tie *NEVER, 'Catch2' or die "tie failed: $!";
{
    local $! = 0;
    my $result = open(my $dup, '>&NEVER');
    ok(!$result, 'dup of a tied glob with no underlying handle fails');
    is($!  + 0, 9, 'and sets $! to EBADF');
}
untie *NEVER;

# The CPAN.pm pattern: STDOUT itself is tied.  Untie before reporting so the
# TAP output is not swallowed by the tie.
{
    tie *STDOUT, 'Catch' or die "tie failed: $!";
    my $out = *SAVEOUT2;
    my $result = open($out, '>&STDOUT');
    close $out if $result;
    untie *STDOUT;
    ok($result, 'dup of a tied STDOUT succeeds');
}
