#!/usr/bin/env perl
use strict;
use warnings;

# Check Opcodes.java for duplicate or out-of-order opcode numbers.
# Optionally renumber the absolute block (284+) to be contiguous after
# the last hand-assigned opcode.
#
# Usage (run from repo root):
#   perl dev/tools/check_opcodes.pl src/main/java/org/perlonjava/backend/bytecode/Opcodes.java
#   perl dev/tools/check_opcodes.pl src/main/java/org/perlonjava/backend/bytecode/Opcodes.java --renumber

my ($file, $flag) = @ARGV;
die "Usage: $0 Opcodes.java [--renumber]\n" unless $file && -f $file;
my $renumber = ($flag // '') eq '--renumber';

my $content = do { open my $fh, '<', $file or die "Cannot open $file: $!"; local $/; <$fh> };

# Collect all  public static final short NAME = NUMBER;  (skip LASTOP+N expressions)
my (%name2num, %num2names);
while ($content =~ /\bpublic\s+static\s+final\s+short\s+(\w+)\s*=\s*(\d+)\s*;/g) {
    my ($name, $num) = ($1, $2);
    $name2num{$name} = $num;
    push @{ $num2names{$num} }, $name;
}

# --- Duplicates ---
my @dups = sort { $a <=> $b } grep { @{ $num2names{$_} } > 1 } keys %num2names;
if (@dups) {
    print "DUPLICATES:\n";
    for my $n (@dups) {
        print "  $n => ", join(", ", @{ $num2names{$n} }), "\n";
    }
} else {
    print "No duplicates.\n";
}

# --- Range and gaps ---
my @sorted = sort { $a <=> $b } keys %num2names;
printf "Range: %d..%d  (%d distinct values)\n", $sorted[0], $sorted[-1], scalar @sorted;

my @gaps;
for my $i (1 .. $#sorted) {
    my ($prev, $cur) = @sorted[ $i - 1, $i ];
    push @gaps, sprintf("  %d..%d  (gap of %d)", $prev, $cur, $cur - $prev - 1) if $cur - $prev > 1;
}
if (@gaps) {
    print "GAPS:\n";
    print "$_\n" for @gaps;
} else {
    print "No gaps.\n";
}

# --- Renumber ---
if ($renumber) {
    # Find the last opcode below 284 (the hand-assigned range)
    my @low  = sort { $a <=> $b } grep { $_ < 284 } keys %num2names;
    my $next = ($low[-1] // 283) + 1;
    print "\nRenumbering 284+ starting at $next:\n";

    my @high = sort { $a <=> $b } grep { $_ >= 284 } keys %num2names;
    my %remap;
    for my $old (@high) {
        $remap{$old} = $next++;
    }

    for my $old (sort { $a <=> $b } keys %remap) {
        my $new = $remap{$old};
        next if $old == $new;
        my @names = @{ $num2names{$old} };
        printf "  %d -> %d  (%s)\n", $old, $new, join(", ", @names);
        for my $name (@names) {
            # Replace  NAME = OLD;  with  NAME = NEW;
            $content =~ s/\b(\Q$name\E\s*=\s*)\d+(\s*;)/$1$new$2/;
        }
    }

    open my $fh, '>', $file or die "Cannot write $file: $!";
    print $fh $content;
    print "Written.\n";
}
