use strict;
use warnings;
use threads;
use threads::shared;

print "1..9\n";

package CallbackTiedScalar;
sub TIESCALAR { bless \(my $value = $_[1]), $_[0] }
sub FETCH { ${$_[0]} }
sub STORE { ${$_[0]} = $_[1] }

package CallbackTiedArray;
sub TIEARRAY { bless [@_[1 .. $#_]], $_[0] }
sub FETCHSIZE { scalar @{$_[0]} }
sub FETCH { $_[0][$_[1]] }
sub STORE { $_[0][$_[1]] = $_[2] }

package CallbackTiedHash;
sub TIEHASH { bless { x => 1 }, $_[0] }
sub FETCH { $_[0]{$_[1]} }
sub STORE { $_[0]{$_[1]} = $_[2] }

package main;
tie my $tied_scalar, 'CallbackTiedScalar', 1;
tie my @tied_array, 'CallbackTiedArray', 1;
tie my %tied_hash, 'CallbackTiedHash';
my $shared :shared = 1;
my @events;

'ac' =~ /^(?:a(?{
    push @events, 'abandoned';
    $tied_scalar = 2;
    $tied_array[0] = 2;
    $tied_hash{x} = 2;
    $shared = 2;
})b|a(?{ push @events, 'chosen' })c)$/;

print join(',', @events) eq 'abandoned,chosen'
    ? "ok 1 - both callback paths executed\n"
    : "not ok 1 - both callback paths executed\n";
print $tied_scalar == 2
    ? "ok 2 - tied scalar side effect persists\n"
    : "not ok 2 - tied scalar side effect persists\n";
print $tied_array[0] == 2
    ? "ok 3 - tied array side effect persists\n"
    : "not ok 3 - tied array side effect persists\n";
print $tied_hash{x} == 2
    ? "ok 4 - tied hash side effect persists\n"
    : "not ok 4 - tied hash side effect persists\n";
print $shared == 2
    ? "ok 5 - shared side effect persists\n"
    : "not ok 5 - shared side effect persists\n";

my $positioned = 'xyz';
pos($positioned) = 1;
'ac' =~ /^(?:a(?{ pos($positioned) = 2 })b|ac)$/;
print pos($positioned) == 2
    ? "ok 6 - unrelated pos side effect persists\n"
    : "not ok 6 - unrelated pos side effect persists\n";

local $^R = 'initial';
'ac' =~ /^(?:a(?{ $^R = 'abandoned' })b|a(?{ $^R = 'chosen' })c)$/;
print $^R eq 'chosen'
    ? "ok 7 - regex result state follows chosen path\n"
    : "not ok 7 - regex result state follows chosen path\n";

my $readonly = 1;
Internals::SvREADONLY($readonly, 1);
my $error = '';
eval { 'a' =~ /(?{ $readonly = 2 })/; 1 } or $error = $@;
print $readonly == 1
    ? "ok 8 - readonly value remains unchanged\n"
    : "not ok 8 - readonly value remains unchanged\n";
print $error =~ /Modification of a read-only value attempted/
    ? "ok 9 - readonly callback write throws\n"
    : "not ok 9 - readonly callback write throws\n";
