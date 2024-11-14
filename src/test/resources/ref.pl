use strict;
use warnings;
use feature 'say';

# Test ref operator for different types
my $scalar = 42;
print "not " if ref($scalar) ne ""; say "ok # Scalar ref <" . ref($scalar) . ">";

my $array_ref = [1, 2, 3];
print "not " if ref($array_ref) ne "ARRAY"; say "ok # Array ref <" . ref($array_ref) . ">";

my $hash_ref = { key => 'value' };
print "not " if ref($hash_ref) ne "HASH"; say "ok # Hash ref <" . ref($hash_ref) . ">";

my $code_ref = sub { return 1; };
print "not " if ref($code_ref) ne "CODE"; say "ok # Code ref <" . ref($code_ref) . ">";

my $glob_ref = *STDOUT;
print "not " if ref($glob_ref) ne ""; say "ok # Glob ref <" . ref($glob_ref) . ">";

my $regex_ref = qr/abc/;
print "not " if ref($regex_ref) ne "Regexp"; say "ok # Regex ref <" . ref($regex_ref) . ">";

my $vstring = v97;
print "not " if ref(\$vstring) ne "VSTRING"; say "ok # VSTRING ref <" . ref(\$vstring) . ">";

# Test stringification of different types
print "not " if "$scalar" ne "42"; say "ok # Scalar stringification <" . "$scalar" . ">";
print "not " if "$array_ref" ne "ARRAY(0x" . sprintf("%x", 0 + $array_ref) . ")"; say "ok # Array stringification <" . "$array_ref" . ">";
print "not " if "$hash_ref" ne "HASH(0x" . sprintf("%x", 0 + $hash_ref) . ")"; say "ok # Hash stringification <" . "$hash_ref" . ">";
print "not " if "$code_ref" ne "CODE(0x" . sprintf("%x", 0 + $code_ref) . ")"; say "ok # Code stringification <" . "$code_ref" . ">";
print "not " if "$glob_ref" ne "*main::STDOUT"; say "ok # Glob stringification <" . "$glob_ref" . ">";

## XXX TODO - Java doesn't have "(?^: ... )"
## print "not " if "$regex_ref" ne "(?^:abc)"; say "ok # Regex stringification <" . "$regex_ref" . ">";

print "not " if "$vstring" ne "a"; say "ok # VSTRING stringification <" . "$vstring" . ">";

# Test reference types
my $scalar_ref = \$scalar;
print "not " if ref($scalar_ref) ne "SCALAR"; say "ok # Scalar reference type <" . ref($scalar_ref) . ">";

my $array_ref_ref = \$array_ref;
print "not " if ref($array_ref_ref) ne "REF"; say "ok # Array reference type <" . ref($array_ref_ref) . ">";

my $hash_ref_ref = \$hash_ref;
print "not " if ref($hash_ref_ref) ne "REF"; say "ok # Hash reference type <" . ref($hash_ref_ref) . ">";

my $code_ref_ref = \$code_ref;
print "not " if ref($code_ref_ref) ne "REF"; say "ok # Code reference type <" . ref($code_ref_ref) . ">";

my $glob_ref_ref = \$glob_ref;
print "not " if ref($glob_ref_ref) ne "GLOB"; say "ok # Glob reference type <" . ref($glob_ref_ref) . ">";

$glob_ref_ref = \*STDOUT;
print "not " if ref($glob_ref_ref) ne "GLOB"; say "ok # Glob reference type <" . ref($glob_ref_ref) . ">";

my $regex_ref_ref = \$regex_ref;
print "not " if ref($regex_ref_ref) ne "REF"; say "ok # Regex reference type <" . ref($regex_ref_ref) . ">";

my $vstring_ref = \$vstring;
print "not " if ref($vstring_ref) ne "VSTRING"; say "ok # VSTRING reference type <" . ref($vstring_ref) . ">";

{
# Test stash entries (symbol table entries)
my $stash_entry = *main::;
print "not " if ref($stash_entry) ne ""; say "ok # Stash entry ref <" . ref($stash_entry) . ">";

my $stash_entry_ref = \*main::;
print "not " if ref($stash_entry_ref) ne "GLOB"; say "ok # Stash entry ref <" . ref($stash_entry_ref) . ">";

## Test stringification of stash entries
## XXX TODO
## print "not " if "$stash_entry" ne "*main::main::"; say "ok # Stash entry stringification <" . "$stash_entry" . ">";
}

{
# Test stash entries (symbol table entries)
# Note: $Testing::a was never used
my $stash_entry = $Testing::{a};
print "not " if defined($stash_entry); say "ok # Stash entry ref <" . ref($stash_entry) . ">";

my $stash_entry_ref = \$Testing::{a};
print "not " if ref($stash_entry_ref) ne "SCALAR"; say "ok # Stash entry ref <" . ref($stash_entry_ref) . ">";

## Test stringification of stash entries
print "not " if defined($stash_entry); say "ok # Stash entry stringification";
}

{
# Test stash entries (symbol table entries)
# Note: now $Testing2::a is being initialized
$Testing2::a = 123;
my $stash_entry = $Testing2::{a};
print "not " if ref($stash_entry) ne ""; say "ok # Stash entry ref <" . ref($stash_entry) . ">";

my $stash_entry_ref = \$Testing2::{a};
print "not " if ref($stash_entry_ref) ne "GLOB"; say "ok # Stash entry ref <" . ref($stash_entry_ref) . ">";

## Test stringification of stash entries
print "not " if "$stash_entry" ne "*Testing2::a"; say "ok # Stash entry stringification <" . "$stash_entry" . ">";
}
