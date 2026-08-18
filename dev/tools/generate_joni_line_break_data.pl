#!/usr/bin/env perl
use strict;
use warnings;
use Digest::SHA qw(sha256_hex);
use File::Spec;
use FindBin;

my $expected_version = '17.0.0';
my $unicore = File::Spec->catdir($FindBin::Bin, '..', '..', 'perl5', 'lib', 'unicore');
my %sources = (
    Line_Break => [File::Spec->catfile($unicore, 'LineBreak.txt'),
        'e6a18fa91f8f6a6f8e534b1d3f128c21ada45bfe152eb6b1bcc5e15fd8ac92e6'],
    General_Category => [File::Spec->catfile($unicore, 'UnicodeData.txt'),
        '2e1efc1dcb59c575eedf5ccae60f95229f706ee6d031835247d843c11d96470c'],
    East_Asian_Width => [File::Spec->catfile($unicore, 'EastAsianWidth.txt'),
        'ea7ce50f3444a050333448dffef1cadd9325af55cbb764b4a2280faf52170a33'],
    Extended_Pictographic => [File::Spec->catfile($unicore, 'emoji', 'emoji.txt'),
        '2cb2bb9455cda83e8481541ecf5b6dfda66a3bb89efa3fa7c5297eccf607b72b'],
);

sub source_text {
    my ($property) = @_;
    my ($path, $expected_hash) = @{$sources{$property}};
    open my $input, '<:raw', $path or die "Cannot read $path: $!\n";
    local $/;
    my $text = <$input>;
    close $input or die "Cannot close $path: $!\n";
    my $actual_hash = sha256_hex($text);
    die "$path SHA-256 mismatch: expected $expected_hash, found $actual_hash\n"
        unless $actual_hash eq $expected_hash;
    return ($path, $text);
}

sub range_from_text {
    my ($range) = @_;
    my ($first, $last) = split /\.\./, $range;
    return (hex($first), hex(defined $last ? $last : $first));
}

sub parse_property_file {
    my ($property, $version_pattern, $wanted_value, $default) = @_;
    my ($path, $text) = source_text($property);
    die "$path is not pinned Unicode $expected_version data\n"
        unless $text =~ $version_pattern;
    my (@ranges, @missing);
    for my $line (split /\n/, $text) {
        if ($line =~ /^#\s*\@missing:\s*([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*([A-Za-z0-9_]+)/) {
            my ($range, $value) = ($1, $2);
            my ($first, $last) = range_from_text($range);
            push @missing, [$first, $last, $value];
            next;
        }
        next unless $line =~ /^([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*([A-Za-z0-9_]+)/;
        my ($range, $value) = ($1, $2);
        next if defined $wanted_value && $value ne $wanted_value;
        my ($first, $last) = range_from_text($range);
        push @ranges, [$first, $last, defined $wanted_value ? 'Y' : $value];
    }
    return {ranges => \@ranges, missing => \@missing, default => $default};
}

sub parse_general_category {
    my ($path, $text) = source_text('General_Category');
    my (@ranges, $pending);
    for my $line (split /\n/, $text) {
        my @fields = split /;/, $line, -1;
        next unless @fields >= 3;
        my ($code, $name, $category) = (hex($fields[0]), $fields[1], $fields[2]);
        if ($name =~ /, First>$/) {
            $pending = [$code, $category];
        } elsif ($name =~ /, Last>$/) {
            die "Unmatched UnicodeData Last range at $fields[0]\n"
                unless $pending && $pending->[1] eq $category;
            push @ranges, [$pending->[0], $code, $category];
            undef $pending;
        } else {
            push @ranges, [$code, $code, $category];
        }
    }
    die "Unclosed UnicodeData First range\n" if $pending;
    return {ranges => \@ranges, missing => [], default => 'Cn'};
}

my @classes = qw(
    AI AK AL AP AS B2 BA BB BK CB CJ CL CM CP CR EB EM EX GL H2 H3 HH HL HY
    ID IN IS JL JT JV LF NL NS NU OP PO PR QU RI SA SG SP SY VF VI WJ XX ZW ZWJ
);
my %class_id;
@class_id{@classes} = (0 .. $#classes);

my %maps = (
    Line_Break => parse_property_file('Line_Break', qr/^# LineBreak-\Q$expected_version\E\.txt/m, undef, 'XX'),
    General_Category => parse_general_category(),
    East_Asian_Width => parse_property_file('East_Asian_Width', qr/^# EastAsianWidth-\Q$expected_version\E\.txt/m, undef, 'N'),
    Extended_Pictographic => parse_property_file('Extended_Pictographic', qr/^# Version: 17\.0$/m,
        'Extended_Pictographic', 'N'),
);

my %starts = (0 => 1);
for my $map (values %maps) {
    for my $range (@{$map->{ranges}}, @{$map->{missing}}) {
        $starts{$range->[0]} = 1;
        $starts{$range->[1] + 1} = 1 if $range->[1] < 0x10ffff;
    }
}
my @starts = sort { $a <=> $b } keys %starts;

sub value_at {
    my ($map, $code) = @_;
    my $ranges = $map->{ranges};
    my ($low, $high) = (0, $#$ranges);
    while ($low <= $high) {
        my $middle = ($low + $high) >> 1;
        if ($ranges->[$middle][0] <= $code) {
            $low = $middle + 1;
        } else {
            $high = $middle - 1;
        }
    }
    return $ranges->[$high][2] if $high >= 0 && $code <= $ranges->[$high][1];
    my $value = $map->{default};
    for my $range (@{$map->{missing}}) {
        $value = $range->[2] if $range->[0] <= $code && $code <= $range->[1];
    }
    return $value;
}

my @values;
for my $code (@starts) {
    my $lb = value_at($maps{Line_Break}, $code);
    my $gc = value_at($maps{General_Category}, $code);
    my $ea = value_at($maps{East_Asian_Width}, $code);
    my $ep = value_at($maps{Extended_Pictographic}, $code);

    $lb = 'AL' if $lb eq 'AI' || $lb eq 'SG' || $lb eq 'XX';
    $lb = $gc eq 'Mn' || $gc eq 'Mc' ? 'CM' : 'AL' if $lb eq 'SA';
    $lb = 'NS' if $lb eq 'CJ';
    die "Unknown Line_Break value '$lb'\n" unless exists $class_id{$lb};

    my $packed = $class_id{$lb};
    $packed |= 1 << 6 if $gc eq 'Pi';
    $packed |= 1 << 7 if $gc eq 'Pf';
    $packed |= 1 << 8 if $ea eq 'F' || $ea eq 'W' || $ea eq 'H';
    $packed |= 1 << 9 if $ep eq 'Y' && $gc eq 'Cn';
    push @values, $packed;
}

# Coalesce adjacent union intervals whose packed value is identical.
my (@coalesced_starts, @coalesced_values);
for my $i (0 .. $#starts) {
    next if @coalesced_values && $values[$i] == $coalesced_values[-1];
    push @coalesced_starts, $starts[$i];
    push @coalesced_values, $values[$i];
}

print <<'HEADER';
/*
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.joni;

// Generated by dev/tools/generate_joni_line_break_data.pl. Do not edit manually.
final class LineBreakData {
HEADER

print "    static final String UNICODE_VERSION = \"$expected_version\";\n";
for my $class (@classes) {
    print "    static final short $class = $class_id{$class};\n";
}
print <<'FLAGS';
    static final short CLASS_MASK = 0x3f;
    static final short INITIAL_PUNCTUATION = 1 << 6;
    static final short FINAL_PUNCTUATION = 1 << 7;
    static final short EAST_ASIAN = 1 << 8;
    static final short UNASSIGNED_EXTENDED_PICTOGRAPHIC = 1 << 9;

FLAGS

print "    private static final int[] STARTS = {\n";
for (my $i = 0; $i < @coalesced_starts; $i += 10) {
    my $end = $i + 9 < $#coalesced_starts ? $i + 9 : $#coalesced_starts;
    print "        ", join(', ', map { sprintf '0x%X', $coalesced_starts[$_] } $i .. $end), ",\n";
}
print "    };\n\n    private static final short[] VALUES = {\n";
for (my $i = 0; $i < @coalesced_values; $i += 16) {
    my $end = $i + 15 < $#coalesced_values ? $i + 15 : $#coalesced_values;
    print "        ", join(', ', @coalesced_values[$i .. $end]), ",\n";
}
print <<'FOOTER';
    };

    static short propertyOf(int codePoint) {
        int low = 0;
        int high = STARTS.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (STARTS[middle] <= codePoint) {
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return VALUES[Math.max(0, high)];
    }

    static short lineClass(short property) {
        return (short)(property & CLASS_MASK);
    }

    static boolean has(short property, short flag) {
        return (property & flag) != 0;
    }

    private LineBreakData() {
    }
}
FOOTER
