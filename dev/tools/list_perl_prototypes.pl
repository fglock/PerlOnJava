#!/usr/bin/perl
use 5.38.0;
use strict;
use warnings;

# Instructions: 
# Run this script in the root directory of Perl source code

my @opcodes;
open my $fh, '<', 'keywords.h' or die "Can't read keywords.h: $!";
while (<$fh>) {
    my $opcode = lc($1) if /define\sKEY_(\w+)\s/;
    next if !defined $opcode || length($opcode) < 1;
    eval {
        prototype "CORE::$opcode";
        push @opcodes, $opcode;
    };
}
close $fh;

# Generate Java code with static block initialization
print <<'JAVA_HEADER';
    public static final Map<String, String> CORE_PROTOTYPES = new HashMap<>();
    
    static {
JAVA_HEADER

# Generate put statements with proper escaping
for my $k (sort @opcodes) {
    my $v = prototype("CORE::$k");
    $v =~ s/\\/\\\\/g if defined $v;  # Escape backslashes for Java
    $v = defined $v ? "\"$v\"" : "null";
    printf "        CORE_PROTOTYPES.put(\"%s\", %s);\n", $k, $v;
}

print <<'JAVA_FOOTER';
    }
JAVA_FOOTER
