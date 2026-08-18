#!/usr/bin/env perl
use strict;
use warnings;

my $unicode_root = 'perl5/lib/unicore';
my $expected_unicode_version = '17.0.0';

open my $version_fh, '<', "$unicode_root/version"
    or die "Can't read Unicode version: $!\n";
chomp(my $unicode_version = <$version_fh>);
close $version_fh;
die "Expected Unicode $expected_unicode_version, found $unicode_version\n"
    unless $unicode_version eq $expected_unicode_version;

open my $age_fh, '<', "$unicode_root/DAge.txt"
    or die "Can't read Age data: $!\n";
my (@versions, %version_index, @ranges);
while (<$age_fh>) {
    next unless /^([0-9A-F]+)(?:\.\.([0-9A-F]+))?\s*;\s*([0-9]+\.[0-9]+)/;
    my ($start, $end, $version) = (hex($1), defined($2) ? hex($2) : hex($1), $3);
    if (!exists $version_index{$version}) {
        $version_index{$version} = scalar @versions;
        push @versions, $version;
    }
    if (@ranges && $ranges[-1][2] == $version_index{$version}
            && $ranges[-1][1] + 1 == $start) {
        $ranges[-1][1] = $end;
    } else {
        push @ranges, [$start, $end, $version_index{$version}];
    }
}
close $age_fh;

die "Age data does not end at Unicode 17.0\n"
    unless @versions && $versions[-1] eq '17.0';

print <<'HEADER';
/*
 * Generated from Perl 5.44's Unicode Character Database. Do not edit manually.
 *
 * Unicode data source copyright:
 * © 2025 Unicode®, Inc.
 * Unicode and the Unicode Logo are registered trademarks of Unicode, Inc. in
 * the U.S. and other countries.
 * For terms of use and license, see https://www.unicode.org/terms_of_use.html
 */
package org.perlonjava.runtime.regex;

import com.ibm.icu.text.UnicodeSet;

final class PerlUnicodeAgeData {
HEADER

print "    static final String UNICODE_VERSION = \"$unicode_version\";\n";
print "    private static final String[] VERSIONS = {\n        ";
print join(', ', map { qq{"$_"} } @versions);
print "\n    };\n\n";
print "    private static final int[] RANGES = {\n";
for (my $i = 0; $i < @ranges; $i += 4) {
    my $end = $i + 3 < $#ranges ? $i + 3 : $#ranges;
    print "        ", join(', ', map {
        sprintf '0x%X, 0x%X, %d', @{$ranges[$_]}[0, 1, 2]
    } $i .. $end), ",\n";
}
print <<'FOOTER';
    };

    private static final UnicodeSet[] EXACT_SETS = buildExactSets();
    private static final UnicodeSet[] CUMULATIVE_SETS = buildCumulativeSets();
    private static final UnicodeSet UNASSIGNED = new UnicodeSet(0, 0x10ffff)
            .removeAll(CUMULATIVE_SETS[CUMULATIVE_SETS.length - 1]).freeze();

    static UnicodeSet exactSet(String version) {
        int versionIndex = versionIndex(version);
        return versionIndex < 0 ? null : EXACT_SETS[versionIndex];
    }

    static UnicodeSet cumulativeSet(String version) {
        int versionIndex = versionIndex(version);
        return versionIndex < 0 ? null : CUMULATIVE_SETS[versionIndex];
    }

    static UnicodeSet unassignedSet() {
        return UNASSIGNED;
    }

    private static UnicodeSet[] buildExactSets() {
        UnicodeSet[] sets = new UnicodeSet[VERSIONS.length];
        for (int i = 0; i < sets.length; i++) sets[i] = new UnicodeSet();
        for (int i = 0; i < RANGES.length; i += 3) {
            sets[RANGES[i + 2]].add(RANGES[i], RANGES[i + 1]);
        }
        for (UnicodeSet set : sets) set.freeze();
        return sets;
    }

    private static UnicodeSet[] buildCumulativeSets() {
        UnicodeSet[] sets = new UnicodeSet[VERSIONS.length];
        UnicodeSet running = new UnicodeSet();
        for (int i = 0; i < sets.length; i++) {
            running.addAll(EXACT_SETS[i]);
            sets[i] = new UnicodeSet(running).freeze();
        }
        return sets;
    }

    private static int versionIndex(String version) {
        for (int i = 0; i < VERSIONS.length; i++) {
            if (VERSIONS[i].equals(version)) return i;
        }
        return -1;
    }

    private PerlUnicodeAgeData() {
    }
}
FOOTER
