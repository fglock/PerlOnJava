#!./perl

# $Id: dclone.t,v 0.11 2001/07/29 19:31:05 ray Exp $
#
# Id: dclone.t,v 0.6.1.1 2000/03/02 22:21:05 ram Exp 
#
#  Copyright (c) 1995-1998, Raphael Manfredi
#  
#  You may redistribute this file under the same terms as Perl 5 itself.
#
# $Log: dclone.t,v $
# Revision 0.11  2001/07/29 19:31:05  ray
# VERSION 0.11
#
# Revision 0.10.2.1  2001/07/28 21:47:49  ray
# commented out print statements.
#
# Revision 0.10  2001/04/29 21:56:10  ray
# VERSION 0.10
#
# Revision 0.9  2001/03/05 00:11:49  ray
# version 0.9
#
# Revision 0.9  2000/08/21 23:06:34  ray
# added support for code refs
#
# Revision 0.8  2000/08/11 17:08:36  ray
# Release 0.08.
#
# Revision 0.7  2000/08/01 00:31:42  ray
# release 0.07
#
# Revision 0.6  2000/07/28 21:37:20  ray
# "borrowed" code from Storable
#
# Revision 0.6.1.1  2000/03/02 22:21:05  ram
# patch9: added test case for "undef" bug in hashes
#
# Revision 0.6  1998/06/04  16:08:25  ram
# Baseline for first beta release.
#

require './t/dump.pl';

# use Storable qw(dclone);
use Clone::PP qw(clone);

print "1..9\n";

$a = 'toto';
$b = \$a;
$c = bless {}, CLASS;
$c->{attribute} = 'attrval';
%a = ('key', 'value', 1, 0, $a, $b, 'cvar', \$c);
@a = ('first', undef, 3, -4, -3.14159, 456, 4.5,
	$b, \$a, $a, $c, \$c, \%a);

print "not " unless defined ($aref = clone(\@a));
print "ok 1\n";

$dumped = &dump(\@a);
print "ok 2\n";

$got = &dump($aref);
print "ok 3\n";

# print $got;
# print $dumped;
# print $_, "\n" for (@a);
# print $_, "\n" foreach (@$aref);
print "not " unless $got eq $dumped; 
print "ok 4\n";

package FOO; @ISA = qw(Clone::PP);

sub make {
	my $self = bless {};
	$self->{key} = \%main::a;
	return $self;
};

package main;

$foo = FOO->make;
print "not " unless defined($r = $foo->clone);
print "ok 5\n";

# print &dump($foo);
# print &dump($r);
print "not " unless &dump($foo) eq &dump($r);
print "ok 6\n";

# Ensure refs to "undef" values are properly shared during cloning
my $hash;
push @{$$hash{''}}, \$$hash{a};
print "not " unless $$hash{''}[0] == \$$hash{a};
print "ok 7\n";

my $cloned = clone(clone($hash));
require Data::Dumper;

# warn "Hash: " . ( $$hash{''}[0] )   . " : " . ( \$$hash{a} )   . "\n";
# warn "Copy: " . ( $$cloned{''}[0] ) . " : " . ( \$$cloned{a} ) . "\n";

warn "This test is todo " unless $$cloned{''}[0] == \$$cloned{a};
print "ok 8\n";

$$cloned{a} = "blah";
warn "This test is todo " unless $$cloned{''}[0] == \$$cloned{a};
print "ok 9\n";

