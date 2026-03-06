#!/usr/bin/env perl
use strict;
use warnings;

# Check Opcodes.java for duplicate or out-of-order opcode numbers.
# Optionally renumber to fix duplicates and make the 284+ block contiguous
# after the last hand-assigned opcode.
#
# Usage (run from repo root):
#   perl dev/tools/check_opcodes.pl src/main/java/org/perlonjava/backend/bytecode/Opcodes.java
#   perl dev/tools/check_opcodes.pl src/main/java/org/perlonjava/backend/bytecode/Opcodes.java --renumber

my ($file, $flag) = @ARGV;
die "Usage: $0 Opcodes.java [--renumber]\n" unless $file && -f $file;
my $renumber = ($flag // '') eq '--renumber';

my $content = do { open my $fh, '<', $file or die "Cannot open $file: $!"; local $/; <$fh> };

# Collect all  public static final short NAME = NUMBER;  in file order
# (skip LASTOP+N expressions)
my (%name2num, %num2names, @order);
while ($content =~ /\bpublic\s+static\s+final\s+short\s+(\w+)\s*=\s*(\d+)\s*;/g) {
    my ($name, $num) = ($1, $2);
    $name2num{$name} = $num;
    push @{ $num2names{$num} }, $name;
    push @order, $name;
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
    my %used;
    my %name_remap;

    # First pass: mark all currently used numbers, detect duplicates
    my %seen_num;
    for my $name (@order) {
        my $num = $name2num{$name};
        if ($seen_num{$num}++) {
            $name_remap{$name} = undef;  # needs a new number
        } else {
            $used{$num} = 1;
        }
    }

    # Assign new numbers to duplicates using the first available gap
    if (%name_remap) {
        print "\nFixing duplicates:\n";
        my $max = (sort { $a <=> $b } keys %used)[-1];
        my $candidate = 1;
        for my $name (@order) {
            next unless exists $name_remap{$name} && !defined $name_remap{$name};
            $candidate++ while $used{$candidate};
            $name_remap{$name} = $candidate;
            $used{$candidate} = 1;
            printf "  %s: %d -> %d\n", $name, $name2num{$name}, $candidate;
            $name2num{$name} = $candidate;
            $candidate++;
        }
        # Rebuild num2names after dedup
        %num2names = ();
        for my $name (@order) {
            push @{ $num2names{ $name2num{$name} } }, $name;
        }
    }

    # Second pass: renumber 284+ block to be contiguous
    my @low  = sort { $a <=> $b } grep { $_ < 284 } keys %num2names;
    my $next = ($low[-1] // 283) + 1;
    print "\nRenumbering 284+ starting at $next:\n";

    my @high = sort { $a <=> $b } grep { $_ >= 284 } keys %num2names;
    my %remap;
    for my $old (@high) {
        $remap{$old} = $next++;
    }

    # Apply all changes (dedup + renumber) to file content
    for my $name (@order) {
        my $old_in_file;
        if ($content =~ /\b\Q$name\E\s*=\s*(\d+)\s*;/) {
            $old_in_file = $1;
        } else {
            next;
        }
        my $new;
        if (exists $name_remap{$name}) {
            $new = $name_remap{$name};
        } elsif (exists $remap{$old_in_file + 0}) {
            $new = $remap{$old_in_file + 0};
        } else {
            next;
        }
        next if $old_in_file == $new;
        printf "  %d -> %d  (%s)\n", $old_in_file, $new, $name unless exists $name_remap{$name};
        $content =~ s/\b(\Q$name\E\s*=\s*)\d+(\s*;)/$1$new$2/;
    }

    open my $fh, '>', $file or die "Cannot write $file: $!";
    print $fh $content;
    print "Written.\n";
}
