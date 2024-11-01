use feature 'say';
use strict;

###################
# Perl V-String Operations Tests

# V-String creation and assignment
my $vstring = v1.2.3;
print "not " if $vstring ne "\x01\x02\x03";
say "ok # V-String creation and assignment";

my $ref = ref(\$vstring);
print "not " if $ref ne "VSTRING";
say "ok # V-String ref <$ref>";

# V-String with leading zero
$vstring = v0.10.20;
print "not " if $vstring ne "\x00\x0A\x14";
say "ok # V-String with leading zero";

# V-String comparison
my $vstring1 = v1.2.3;
my $vstring2 = v1.2.4;
print "not " if $vstring1 ge $vstring2;
say "ok # V-String comparison";

# V-String concatenation
my $concat_vstring = $vstring1 . $vstring2;
print "not " if $concat_vstring ne "\x01\x02\x03\x01\x02\x04";
say "ok # V-String concatenation";

# V-String length
my $length = length $vstring1;
print "not " if $length != 3;
say "ok # V-String length";

# V-String to number conversion
my $number = ord(substr($vstring1, 0, 1));
print "not " if $number != 1;
say "ok # V-String to number conversion";

# V-String in array context
my @vstring_array = (v1.2.3, v4.5.6);
print "not " if $vstring_array[0] ne "\x01\x02\x03" or $vstring_array[1] ne "\x04\x05\x06";
say "ok # V-String in array context";

# V-String split operation
my @split_vstring = split //, $vstring1;
print "not " if @split_vstring != 3 or $split_vstring[0] ne "\x01" or $split_vstring[2] ne "\x03";
say "ok # V-String split operation";

# V-String join operation
my $joined_vstring = join '', @split_vstring;
print "not " if $joined_vstring ne $vstring1;
say "ok # V-String join operation";

# Perl V-String References Tests

# V-String reference creation
$vstring = v1.2.3;
my $vstring_ref = \$vstring;
print "not " if $$vstring_ref ne "\x01\x02\x03";
say "ok # V-String reference creation";

# Modify v-string through reference
$$vstring_ref = v4.5.6;
print "not " if $vstring ne "\x04\x05\x06";
say "ok # Modify v-string through reference";

