#!/usr/bin/env perl
use strict;
use warnings;

use Test::More;

my $scalar = 1;
undef $scalar;
ok !defined($scalar), 'undef clears scalar lvalue';

my $code = sub { 1 };
undef $code;
ok !defined($code), 'undef clears coderef scalar lvalue';

our $x;
sub __ANON__ { print "unexpected call\n" }
undef &{$x = sub { print "unexpected call\n" }};
my $error = eval { $x->(); 1 };
like($@, qr/^Undefined subroutine called at /,
    'undef &{...} clears an anonymous CV in place');

my $array = [ sub { 1 }, sub { 2 } ];
undef $array->[1];
ok !defined($array->[1]), 'undef clears array element lvalue';
ok ref($array->[0]) eq 'CODE', 'undef leaves other array elements intact';

my $hash = { cb => sub { 3 } };
undef $hash->{cb};
ok !defined($hash->{cb}), 'undef clears hash element lvalue';

done_testing;
