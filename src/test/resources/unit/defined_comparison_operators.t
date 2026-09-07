use strict;
use warnings;
my ($a, $b);

print "1..8\n";
print "ok 1 - equ compares defined strings\n" if 'abc' equ 'abc';
print "ok 2 - equ distinguishes different strings\n" if !('abc' equ 'def');
print "ok 3 - equ considers two undefs equal\n" if !defined($a) && !defined($b) && ($a equ $b);
print "ok 4 - equ distinguishes undef from empty string\n" if !($a equ '');
print "ok 5 - neu is the inverse of equ\n" if 'abc' neu 'def';
print "ok 6 - strict numeric equality compares numbers\n" if 123 === 123;
print "ok 7 - strict numeric equality considers two undefs equal\n" if $a === $b;
print "ok 8 - strict numeric inequality distinguishes undef and zero\n" if $a !== 0;
