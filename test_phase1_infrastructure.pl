#!/usr/bin/perl

# Test script to verify Phase 1 sublanguage parser infrastructure
# This should trigger the debug output we added to StringParser.java

print "Testing Phase 1 sublanguage parser infrastructure...\n";

# Test regex parsing (should trigger validateRegex debug output)
my $regex_test = qr/test[a-z]+/i;
print "Regex test: $regex_test\n";

# Test pack parsing (should trigger validatePack debug output)  
my $pack_test = pack("A10", "hello");
print "Pack test: " . unpack("A10", $pack_test) . "\n";

# Test sprintf parsing (should trigger validateSprintf debug output)
my $sprintf_test = sprintf("%s %d", "test", 42);
print "Sprintf test: $sprintf_test\n";

# Test transliteration parsing (should trigger validateTransliteration debug output)
my $tr_test = "Hello World";
$tr_test =~ tr/A-Z/a-z/;
print "Transliteration test: $tr_test\n";

print "Phase 1 infrastructure test complete!\n";
