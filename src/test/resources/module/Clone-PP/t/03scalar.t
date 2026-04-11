# Before `make install' is performed this script should be runnable with
# `make test'. After `make install' it should work as `perl test.pl'

######################### We start with some black magic to print on failure.

# Change 1..1 below to 1..last_test_to_print .
# (It may become useful if the test is moved to ./t subdirectory.)

BEGIN { $| = 1; print "1..6\n"; }
END {print "not ok 1\n" unless $loaded;}
use Clone::PP qw( clone );
use Data::Dumper;
$loaded = 1;
print "ok 1\n";

######################### End of black magic.

# Insert your test code below (better if it prints "ok 13"
# (correspondingly "not ok 13") depending on the success of chunk 13
# of the test code):

package Test::Scalar;

use vars @ISA;

@ISA = qw(Clone::PP);

sub new
  {
    my $class = shift;
    my $self = shift;
    bless \$self, $class;
  }

sub DESTROY 
  {
    my $self = shift;
    # warn "DESTROYING $self";
  }

package main;
                                                
sub ok     { print "ok $test\n"; $test++ }
sub not_ok { print "not ok $test\n"; $test++ }

$^W = 0;
$test = 2;

my $a = Test::Scalar->new(1.0);
my $b = $a->clone(1);

$$a == $$b ? ok : not_ok;
$a != $b ? ok : not_ok;

my $c = \"test 2 scalar";
my $d = Clone::PP::clone($c, 2);

$$c == $$d ? ok : not_ok;
$c != $d ? ok : not_ok;

my $circ = undef;
$circ = \$circ;
$aref = clone($circ);
Dumper($circ) eq Dumper($aref) ? ok : not_ok;
