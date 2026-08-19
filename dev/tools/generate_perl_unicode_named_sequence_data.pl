#!/usr/bin/env perl
use strict;
use warnings;

# Unicode data source copyright:
# © 2025 Unicode®, Inc.
# Unicode and the Unicode Logo are registered trademarks of Unicode, Inc. in
# the U.S. and other countries.
# For terms of use and license, see https://www.unicode.org/terms_of_use.html

my $unicode_root = 'perl5/lib/unicore';
my $source_name = 'NamedSequences.txt';
my $expected_unicode_version = '17.0.0';

open my $version_fh, '<', "$unicode_root/version"
    or die "Can't read Unicode version: $!\n";
chomp(my $unicode_version = <$version_fh>);
close $version_fh;
die "Expected Unicode $expected_unicode_version, found $unicode_version\n"
    unless $unicode_version eq $expected_unicode_version;

open my $sequence_fh, '<', "$unicode_root/$source_name"
    or die "Can't read $source_name: $!\n";
my (@entries, $source_version);
while (<$sequence_fh>) {
    $source_version = $1 if /^# NamedSequences-([0-9.]+)\.txt\s*$/;
    next if /^\s*(?:#|$)/;
    chomp;
    my ($name, $code_points) = split /;/, $_, 2;
    die "Malformed $source_name line: $_\n"
        unless defined $name && defined $code_points
            && $name =~ /\S/ && $code_points =~ /\S/;
    my @code_points = map {
        die "Invalid code point '$_' for $name\n" unless /^[0-9A-F]{4,6}$/;
        hex $_;
    } grep { length } split /\s+/, $code_points;
    die "Named sequence $name has fewer than two code points\n"
        unless @code_points >= 2;
    push @entries, [$name, \@code_points];
}
close $sequence_fh;

die "Expected $source_name version $unicode_version, found "
        . (defined $source_version ? $source_version : 'no version') . "\n"
    unless defined $source_version && $source_version eq $unicode_version;

@entries = sort { $a->[0] cmp $b->[0] } @entries;
for my $index (1 .. $#entries) {
    die "Duplicate named sequence $entries[$index][0]\n"
        if $entries[$index - 1][0] eq $entries[$index][0];
}

sub java_string {
    my ($value) = @_;
    $value =~ s/\\/\\\\/g;
    $value =~ s/"/\\"/g;
    return qq{"$value"};
}

print <<'HEADER';
/*
 * Generated from Perl 5.44's pinned Unicode NamedSequences.txt.
 * Do not edit manually; run dev/tools/generate_perl_unicode_named_sequence_data.pl.
 *
 * Unicode data source copyright:
 * © 2025 Unicode®, Inc.
 * Unicode and the Unicode Logo are registered trademarks of Unicode, Inc. in
 * the U.S. and other countries.
 * For terms of use and license, see https://www.unicode.org/terms_of_use.html
 */
package org.perlonjava.runtime.regex;

import java.util.Arrays;

public final class PerlUnicodeNamedSequenceData {
HEADER

print "    public static final String UNICODE_VERSION = "
        . java_string($unicode_version) . ";\n";
print "    public static final String SOURCE_FILE = "
        . java_string("NamedSequences-$source_version.txt") . ";\n\n";

print "    private static final String[] NAMES = {\n";
for (my $offset = 0; $offset < @entries; $offset += 4) {
    my $end = $offset + 3 < $#entries ? $offset + 3 : $#entries;
    print "        ", join(', ', map { java_string($entries[$_][0]) }
            $offset .. $end), ",\n";
}
print "    };\n\n";

my @offsets = (0);
my @code_points;
for my $entry (@entries) {
    push @code_points, @{$entry->[1]};
    push @offsets, scalar @code_points;
}
print "    private static final int[] OFFSETS = {\n";
for (my $offset = 0; $offset < @offsets; $offset += 16) {
    my $end = $offset + 15 < $#offsets ? $offset + 15 : $#offsets;
    print "        ", join(', ', @offsets[$offset .. $end]), ",\n";
}
print "    };\n\n";

print "    private static final int[] CODE_POINTS = {\n";
for (my $offset = 0; $offset < @code_points; $offset += 12) {
    my $end = $offset + 11 < $#code_points ? $offset + 11 : $#code_points;
    print "        ", join(', ', map { sprintf '0x%X', $_ }
            @code_points[$offset .. $end]), ",\n";
}
print <<'FOOTER';
    };

    public static String sequence(String name) {
        if (name == null) return null;
        int index = Arrays.binarySearch(NAMES, name);
        if (index < 0) return null;
        StringBuilder sequence = new StringBuilder();
        for (int offset = OFFSETS[index]; offset < OFFSETS[index + 1]; offset++) {
            sequence.appendCodePoint(CODE_POINTS[offset]);
        }
        return sequence.toString();
    }

    static int entryCount() {
        return NAMES.length;
    }

    private PerlUnicodeNamedSequenceData() {
    }
}
FOOTER
