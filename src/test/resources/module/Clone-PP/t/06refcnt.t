# Before `make install' is performed this script should be runnable with
# `make test'. After `make install' it should work as `perl test.pl'

######################### We start with some black magic to print on failure.

# Change 1..1 below to 1..last_test_to_print .
# (It may become useful if the test is moved to ./t subdirectory.)

BEGIN { $| = 1; print "1..9\n"; }
END {print "not ok 1\n" unless $loaded;}
use Clone::PP qw( clone );
$loaded = 1;
print "ok 1\n";

######################### End of black magic.

# Insert your test code below (better if it prints "ok 13"
# (correspondingly "not ok 13") depending on the success of chunk 13
# of the test code):

# code to test for memory leaks

use Benchmark;
use Data::Dumper;
# use Storable qw( dclone );

$^W = 0;
$test = 2;

sub ok     { printf("ok %d\n", $test++); }
sub not_ok { printf("not ok %d\n", $test++); }

use strict;

package Test::Hash;

@Test::Hash::ISA = qw( Clone::PP );

sub new()
{
  my ($class) = @_;
  my $self = {};
  bless $self, $class;
}

my $ok = 0;
END { $ok = 1; };
sub DESTROY
{
  my $self = shift;
  printf("not ") if $ok;
  printf("ok %d\n", $::test++);
}

package main;

{
  my $a = Test::Hash->new();
  my $b = $a->clone;
  # my $c = dclone($a);
}

# benchmarking bug
{
  my $a = Test::Hash->new();
  my $sref = sub { my $b = clone($a) };
  $sref->();
}

# test for cloning unblessed ref
{
  my $a = {};
  my $b = clone($a);
  bless $a, 'Test::Hash';
  bless $b, 'Test::Hash';
}

# test for cloning unblessed ref
{
  my $a = [];
  my $b = clone($a);
  bless $a, 'Test::Hash';
  bless $b, 'Test::Hash';
}
