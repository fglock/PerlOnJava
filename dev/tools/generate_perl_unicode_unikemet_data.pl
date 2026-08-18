#!/usr/bin/env perl
use strict;
use warnings;
use Digest::SHA qw(sha256_hex);
use File::Spec;
use FindBin;

binmode STDOUT, ':raw';

my $expected_version = '17.0.0';
my $expected_date = '2025-07-21';
my $expected_hash = '76a3081265e6eb673873f9c93d6f36062e82c7ed027c5c1a592accfbe48c20a5';
my $root = File::Spec->catdir($FindBin::Bin, '..', '..');
my $local_unicore = File::Spec->catdir($root, 'perl5', 'lib', 'unicore');
my $vendored_unicore = File::Spec->catdir($root, 'dev', 'unicode', $expected_version);
my @required_sources = ('version', 'Unikemet.txt');
sub missing_sources {
    my ($source_root) = @_;
    return grep { !-f File::Spec->catfile($source_root, $_) } @required_sources;
}
my @local_missing = missing_sources($local_unicore);
my @vendored_missing = missing_sources($vendored_unicore);
my $unicore = !@local_missing ? $local_unicore
    : !@vendored_missing ? $vendored_unicore
    : die "No complete Unicode $expected_version Unikemet source: local missing "
        . join(', ', @local_missing) . '; vendored missing '
        . join(', ', @vendored_missing) . "\n";
my $source = File::Spec->catfile($unicore, 'Unikemet.txt');
my $version_source = File::Spec->catfile($unicore, 'version');

open my $version_input, '<:raw', $version_source
    or die "Cannot read $version_source: $!\n";
local $/;
my $version = <$version_input>;
close $version_input or die "Cannot close $version_source: $!\n";
$version =~ s/\s+\z//;
die "Expected Unicode $expected_version, found '$version' in $version_source\n"
    unless $version eq $expected_version;

open my $input, '<:raw', $source or die "Cannot read $source: $!\n";
my $text = <$input>;
close $input or die "Cannot close $source: $!\n";
my $actual_hash = sha256_hex($text);
die "$source SHA-256 mismatch: expected $expected_hash, found $actual_hash\n"
    unless $actual_hash eq $expected_hash;
die "$source is not pinned Unikemet $expected_version data\n"
    unless $text =~ /^# Unikemet-\Q$expected_version\E\.txt$/m;
die "$source has an unexpected source date\n"
    unless $text =~ /^# Date: \Q$expected_date\E$/m;
die "$source does not preserve the Unicode copyright notice\n"
    unless $text =~ /^# © 2025 Unicode®, Inc\.$/m;
die "$source does not preserve the Unicode terms notice\n"
    unless $text =~ m{^# For terms of use and license, see https://www\.unicode\.org/terms_of_use\.html$}m;

my %points = (
    kEH_NoMirror => [],
    kEH_NoRotate => [],
);
my %seen;
for my $line (split /\n/, $text) {
    next unless $line =~ /\tkEH_No(?:Mirror|Rotate)\t/;
    die "Malformed Unikemet binary-property record '$line'\n"
        unless $line =~ /^U\+([0-9A-F]{5,6})\t(kEH_NoMirror|kEH_NoRotate)\tY$/;
    my ($code_point, $property) = (hex($1), $2);
    die sprintf "Duplicate %s record for U+%05X\n", $property, $code_point
        if $seen{$property}{$code_point}++;
    push @{$points{$property}}, $code_point;
}

die "Expected 4 kEH_NoMirror points, found " . scalar(@{$points{kEH_NoMirror}}) . "\n"
    unless @{$points{kEH_NoMirror}} == 4;
die "Expected 44 kEH_NoRotate points, found " . scalar(@{$points{kEH_NoRotate}}) . "\n"
    unless @{$points{kEH_NoRotate}} == 44;

sub coalesced_ranges {
    my ($values) = @_;
    my @ranges;
    for my $code_point (sort { $a <=> $b } @$values) {
        if (@ranges && $ranges[-1][1] + 1 == $code_point) {
            $ranges[-1][1] = $code_point;
        } else {
            push @ranges, [$code_point, $code_point];
        }
    }
    return @ranges;
}

my @mirror_ranges = coalesced_ranges($points{kEH_NoMirror});
my @rotate_ranges = coalesced_ranges($points{kEH_NoRotate});
die "Expected 4 kEH_NoMirror ranges, found " . scalar(@mirror_ranges) . "\n"
    unless @mirror_ranges == 4;
die "Expected 36 kEH_NoRotate ranges, found " . scalar(@rotate_ranges) . "\n"
    unless @rotate_ranges == 36;

print <<'HEADER';
/*
 * Generated from Perl 5.44's pinned Unicode Character Database. Do not edit manually.
 *
 * Source: Unikemet-17.0.0.txt
 * © 2025 Unicode®, Inc.
 * Unicode and the Unicode Logo are registered trademarks of Unicode, Inc. in the
 * U.S. and other countries.
 * For terms of use and license, see https://www.unicode.org/terms_of_use.html
 */
package org.perlonjava.runtime.regex;

import com.ibm.icu.text.UnicodeSet;

final class PerlUnicodeUnikemetData {
HEADER

print "    static final String UNICODE_VERSION = \"$expected_version\";\n";
print "    static final String SOURCE_DATE = \"$expected_date\";\n";
print "    static final String UNIKEMET_SHA256 = \"$expected_hash\";\n";
print "    static final int NO_MIRROR_CODE_POINT_COUNT = 4;\n";
print "    static final int NO_ROTATE_CODE_POINT_COUNT = 44;\n\n";

sub print_ranges {
    my ($name, $ranges) = @_;
    print "    private static final int[] ${name}_RANGES = {\n";
    for (my $index = 0; $index < @$ranges; $index += 4) {
        my $last = $index + 3 < $#$ranges ? $index + 3 : $#$ranges;
        print "        ", join(', ', map {
            sprintf '0x%X, 0x%X', @{$ranges->[$_]}[0, 1]
        } $index .. $last), ",\n";
    }
    print "    };\n\n";
}

print_ranges('NO_MIRROR', \@mirror_ranges);
print_ranges('NO_ROTATE', \@rotate_ranges);

print <<'FOOTER';
    private static final UnicodeSet NO_MIRROR = buildSet(NO_MIRROR_RANGES);
    private static final UnicodeSet NO_ROTATE = buildSet(NO_ROTATE_RANGES);

    static UnicodeSet noMirror() {
        return NO_MIRROR;
    }

    static UnicodeSet noRotate() {
        return NO_ROTATE;
    }

    static int noMirrorRangeCount() {
        return NO_MIRROR_RANGES.length / 2;
    }

    static int noRotateRangeCount() {
        return NO_ROTATE_RANGES.length / 2;
    }

    private static UnicodeSet buildSet(int[] ranges) {
        UnicodeSet set = new UnicodeSet();
        for (int index = 0; index < ranges.length; index += 2) {
            set.add(ranges[index], ranges[index + 1]);
        }
        return set.freeze();
    }

    private PerlUnicodeUnikemetData() {
    }
}
FOOTER
