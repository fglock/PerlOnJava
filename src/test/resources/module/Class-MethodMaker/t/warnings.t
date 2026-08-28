#!perl -w
# (X)Emacs mode: -*- cperl -*-

use strict;
use warnings;

=head1 Unit Test Package for Class::MethodMaker

This package tests the basic compilation and working of Class::MethodMaker
similar to basic but with warnings explicitely on to check for 5.13.2-related
new warnings.

=cut

use Data::Dumper        qw( );
use FindBin        1.42 qw( $Bin );
use Test           1.13 qw( ok plan );

use lib $Bin;
use test qw( DATA_DIR
             evcheck );

use vars qw(@MYWARNINGS);
BEGIN { $SIG{__WARN__} = sub { push @MYWARNINGS, $_[0] }; }

BEGIN {
  # 1 for compilation test,
  plan tests  => 3,
       todo   => [],
}

# ----------------------------------------------------------------------------

=head2 Test 1: compilation

This test confirms that the test script and the modules it calls compiled
successfully.

=cut

use Class::MethodMaker;

ok 1, 1, 'compilation';

# -------------------------------------

=head2 Test 2: scalar

=cut

package bob;

local $^W = 1;

use Class::MethodMaker
  [ scalar =>[qw/ foo /] ];

package main;

local $^W = 1;

my $bob = bless {}, 'bob';
print Data::Dumper->Dump([ $bob ], [qw( bob )])
  if $ENV{TEST_DEBUG};
$bob->foo("x");
print Data::Dumper->Dump([ $bob ], [qw( bob )])
  if $ENV{TEST_DEBUG};
ok $bob->foo, "x",                                              'scalar ( 1)';

# ----------------------------------------------------------------------------

ok scalar(@MYWARNINGS), 0, 'no warnings occurred';
