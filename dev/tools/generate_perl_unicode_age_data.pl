#!/usr/bin/env perl
use strict;
use warnings;
use File::Spec;
use FindBin;
use lib File::Spec->catdir($FindBin::Bin, 'lib');
use PerlOnJava::UnicodeGenerator qw(
    emit_java_range_triples read_pinned_source read_unicode_version repo_root
    select_unicode_root verify_unicode_notice
);

my $expected_unicode_version = '17.0.0';
my $unicode_root = select_unicode_root(
    repo_root => repo_root($FindBin::Bin), version => $expected_unicode_version,
    required => [qw(version DAge.txt)]);

my $unicode_version = read_unicode_version(
    path => File::Spec->catfile($unicode_root, 'version'),
    expected => $expected_unicode_version,
    sha256 => '8c30575264b2772c7a69c5bb6069a28f0e0a7a0df735871bde2d99ee674316ac');

my $age_path = File::Spec->catfile($unicode_root, 'DAge.txt');
my $age_text = read_pinned_source(
    path => $age_path,
    sha256 => 'f8ecdf768bdc210f201abd271d9bc587825618a86a7046a8146cc816393f1998');
verify_unicode_notice($age_path, $age_text);
my (@versions, %version_index, @ranges);
for (split /\n/, $age_text) {
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
emit_java_range_triples(\@ranges);
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

    static int valueCount() {
        return VERSIONS.length;
    }

    static String versionAt(int index) {
        return VERSIONS[index];
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
