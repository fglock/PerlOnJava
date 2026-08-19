#!/usr/bin/env perl
use strict;
use warnings;
use MIME::Base64 qw(encode_base64);
use File::Spec;
use FindBin;
use lib File::Spec->catdir($FindBin::Bin, 'lib');
use PerlOnJava::UnicodeGenerator qw(
    read_pinned_source read_unicode_version repo_root select_perl_root
    select_unicode_root verify_unicode_notice
);

binmode STDOUT, ':raw';

my $unicode_version = '17.0.0';
my $version_hash = '8c30575264b2772c7a69c5bb6069a28f0e0a7a0df735871bde2d99ee674316ac';
my %unicode_hash = (
    'CaseFolding.txt' => 'ff8d8fefbf123574205085d6714c36149eb946d717a0c585c27f0f4ef58c4183',
    'SpecialCasing.txt' => 'efc25faf19de21b92c1194c111c932e03d2a5eaf18194e33f1156e96de4c9588',
);
my %perl_hash = (
    'regen/regcharclass_multi_char_folds.pl' => 'b2f896452d2b30da3e04800f478c60c1fd0b03d6b668689b020f1e3cf1f1cdd9',
    'regen/mk_invlists.pl' => '20a6e3d507a66f4594586485568134873485b08e23383f3dc4e6b3047569267b',
    'lib/unicore/mktables' => 'e3ac360c03d18779fea6d6497fbbe53798135da55e3764d3c9f90a79bbf7e8b5',
    'charclass_invlists.inc' => 'c83c6471e7c188f21a20c6285af83f57d0bd09392f3243e4cc3743f0a5d5052c',
    'regcharclass.h' => 'b6005d471764b31d04063ccd561c88d165a2a30f9bae9b75172eb7b59672754e',
    'regen/regcharclass.pl' => '852a8a7814f08a155d79fead2656fe2b4450ab17a2bce8a1127016119c9c3bc3',
);

my $root = repo_root($FindBin::Bin);
my $unicore = select_unicode_root(
    repo_root => $root,
    version => $unicode_version,
    required => ['version', sort keys %unicode_hash],
);
read_unicode_version(
    path => File::Spec->catfile($unicore, 'version'),
    expected => $unicode_version,
    sha256 => $version_hash,
);
my %unicode_text;
for my $relative (sort keys %unicode_hash) {
    my $path = File::Spec->catfile($unicore, split m{/}, $relative);
    $unicode_text{$relative} = read_pinned_source(
        path => $path, sha256 => $unicode_hash{$relative});
    verify_unicode_notice($path, $unicode_text{$relative});
}
die "CaseFolding.txt is not pinned Unicode $unicode_version data\n"
    unless $unicode_text{'CaseFolding.txt'} =~ /^# CaseFolding-\Q$unicode_version\E\.txt$/m;
die "CaseFolding.txt has an unexpected source date\n"
    unless $unicode_text{'CaseFolding.txt'} =~ /^# Date: 2025-07-30, 23:54:36 GMT$/m;
die "SpecialCasing.txt is not pinned Unicode $unicode_version data\n"
    unless $unicode_text{'SpecialCasing.txt'} =~ /^# SpecialCasing-\Q$unicode_version\E\.txt$/m;
die "SpecialCasing.txt has an unexpected source date\n"
    unless $unicode_text{'SpecialCasing.txt'} =~ /^# Date: 2025-07-31, 22:11:55 GMT$/m;

my $perl_root = select_perl_root(
    repo_root => $root,
    unicode_root => $unicore,
    required => [sort keys %perl_hash],
);
my %perl_text;
for my $relative (sort keys %perl_hash) {
    $perl_text{$relative} = read_pinned_source(
        path => File::Spec->catfile($perl_root, split m{/}, $relative),
        sha256 => $perl_hash{$relative},
    );
}
die "Perl multi-fold generator no longer consumes Case_Folding\n"
    unless $perl_text{'regen/regcharclass_multi_char_folds.pl'}
        =~ /prop_invmap\("Case_Folding"\)/;
die "Perl inversion-list generator no longer consumes Case_Folding\n"
    unless $perl_text{'regen/mk_invlists.pl'} =~ /prop_invmap\("Case_Folding"\)/;
die "Perl mktables no longer excludes Turkic case folding\n"
    unless $perl_text{'lib/unicore/mktables'}
        =~ /Skip Turkic case folding, is locale dependent/;
die "Perl mktables no longer derives multi-character-fold metadata\n"
    unless $perl_text{'lib/unicore/mktables'} =~ /_Perl_Folds_To_Multi_Char/
        && $perl_text{'lib/unicore/mktables'} =~ /_Perl_Is_In_Multi_Char_Fold/;
die "Perl generated case-fold inversion map is missing\n"
    unless $perl_text{'charclass_invlists.inc'} =~ /Case_Folding_invmap/;
my $multi_fold_generator_hash = $perl_hash{'regen/regcharclass_multi_char_folds.pl'};
my $regcharclass_generator_hash = $perl_hash{'regen/regcharclass.pl'};
die "Perl generated multi-fold macros lost their source provenance\n"
    unless $perl_text{'regcharclass.h'} =~ /\Q$multi_fold_generator_hash\E regen\/regcharclass_multi_char_folds\.pl/
        && $perl_text{'regcharclass.h'} =~ /\Q$regcharclass_generator_hash\E regen\/regcharclass\.pl/;
die "Perl generated multi-fold source license notice changed\n"
    unless $perl_text{'regcharclass.h'} =~ /Copyright \(C\) 2007, 2011 by Larry Wall and others/
        && $perl_text{'regcharclass.h'} =~ /GNU General Public/
        && $perl_text{'regcharclass.h'} =~ /Artistic License/;

my (%status_count, %status_mapping, %full, %simple, @turkic_sources);
for my $raw_line (split /\n/, $unicode_text{'CaseFolding.txt'}) {
    my $line = $raw_line;
    $line =~ s/#.*//;
    next unless $line =~ /\S/;
    die "Malformed CaseFolding record '$raw_line'\n"
        unless $line =~ /^\s*([0-9A-F]{4,6});\s*([CFST]);\s*([0-9A-F ]+);\s*$/;
    my ($source, $status, $mapping_text) = (hex($1), $2, $3);
    my @mapping = map { hex } split /\s+/, $mapping_text;
    die sprintf "Out-of-range CaseFolding source U+%X\n", $source
        if $source > 0x10ffff;
    die "Empty CaseFolding mapping for U+" . sprintf('%04X', $source) . "\n"
        unless @mapping;
    die "Out-of-range CaseFolding mapping for U+" . sprintf('%04X', $source) . "\n"
        if grep { $_ > 0x10ffff } @mapping;
    die "Duplicate CaseFolding $status record for U+" . sprintf('%04X', $source) . "\n"
        if exists $status_mapping{$source}{$status};
    $status_mapping{$source}{$status} = \@mapping;
    $status_count{$status}++;
    if ($status eq 'C' || $status eq 'F') {
        die "Conflicting default full folds for U+" . sprintf('%04X', $source) . "\n"
            if exists $full{$source};
        $full{$source} = \@mapping;
    }
    if ($status eq 'C' || $status eq 'S') {
        die "Simple fold is not one code point for U+" . sprintf('%04X', $source) . "\n"
            unless @mapping == 1;
        die "Conflicting default simple folds for U+" . sprintf('%04X', $source) . "\n"
            if exists $simple{$source};
        $simple{$source} = \@mapping;
    }
    push @turkic_sources, $source if $status eq 'T';
}
my %expected_status = (C => 1481, F => 104, S => 31, T => 2);
for my $status (sort keys %expected_status) {
    die "Expected $expected_status{$status} CaseFolding $status records, found "
        . ($status_count{$status} // 0) . "\n"
        unless ($status_count{$status} // 0) == $expected_status{$status};
}
die "Unexpected CaseFolding status\n"
    if grep { !exists $expected_status{$_} } keys %status_count;
die "Expected 1,585 default full folds, found " . scalar(keys %full) . "\n"
    unless keys(%full) == 1585;
die "Expected 1,512 default simple folds, found " . scalar(keys %simple) . "\n"
    unless keys(%simple) == 1512;
my $full_point_count = 0;
$full_point_count += @$_ for values %full;
die "Expected 1,705 default full-fold code points, found $full_point_count\n"
    unless $full_point_count == 1705;

my (%parent, %rank);
sub find_root {
    my ($value) = @_;
    $parent{$value} = $value unless exists $parent{$value};
    $parent{$value} = find_root($parent{$value}) if $parent{$value} != $value;
    return $parent{$value};
}
sub union_values {
    my ($left, $right) = @_;
    $left = find_root($left);
    $right = find_root($right);
    return if $left == $right;
    if (($rank{$left} // 0) < ($rank{$right} // 0)) {
        ($left, $right) = ($right, $left);
    }
    $parent{$right} = $left;
    $rank{$left}++ if ($rank{$left} // 0) == ($rank{$right} // 0);
}
union_values($_, $simple{$_}[0]) for keys %simple;
my %simple_group;
push @{$simple_group{find_root($_)}}, $_ for keys %parent;
my @simple_classes = sort { $a->[0] <=> $b->[0] }
    map { [sort { $a <=> $b } @$_] }
    grep { @$_ > 1 } values %simple_group;
my $simple_member_count = 0;
$simple_member_count += @$_ for @simple_classes;
die "Expected 1,482 simple-fold classes, found " . scalar(@simple_classes) . "\n"
    unless @simple_classes == 1482;
die "Expected 2,994 simple-fold class members, found $simple_member_count\n"
    unless $simple_member_count == 2994;
my %class_size_count;
$class_size_count{scalar @$_}++ for @simple_classes;
die "Unexpected simple-fold class-size distribution\n"
    unless ($class_size_count{2} // 0) == 1455
        && ($class_size_count{3} // 0) == 24
        && ($class_size_count{4} // 0) == 3
        && keys(%class_size_count) == 3;

my %reverse;
my %component;
my %multi_length;
for my $source (sort { $a <=> $b } keys %full) {
    my $mapping = $full{$source};
    next unless @$mapping > 1;
    die "Default full fold is longer than three code points\n" if @$mapping > 3;
    push @{$reverse{join(',', @$mapping)}}, $source;
    $component{$_} = 1 for @$mapping;
    $multi_length{scalar @$mapping}++;
}
die "Expected 73 reverse full-fold sequences, found " . scalar(keys %reverse) . "\n"
    unless keys(%reverse) == 73;
my $reverse_source_count = 0;
$reverse_source_count += @$_ for values %reverse;
die "Expected 104 reverse full-fold sources, found $reverse_source_count\n"
    unless $reverse_source_count == 104;
die "Expected 65 full-fold components, found " . scalar(keys %component) . "\n"
    unless keys(%component) == 65;
die "Unexpected multi-character fold length distribution\n"
    unless ($multi_length{2} // 0) == 88 && ($multi_length{3} // 0) == 16
        && keys(%multi_length) == 2;
die "A reverse full fold has more than two sources\n"
    if grep { @$_ > 2 } values %reverse;

my ($special_records, $conditional_records) = (0, 0);
my %special_multi = (lower => 0, title => 0, upper => 0);
for my $raw_line (split /\n/, $unicode_text{'SpecialCasing.txt'}) {
    my $line = $raw_line;
    $line =~ s/#.*//;
    next unless $line =~ /\S/;
    my @fields = split /;/, $line, -1;
    pop @fields while @fields > 4 && $fields[-1] =~ /^\s*$/;
    die "Malformed SpecialCasing record '$raw_line'\n" unless @fields >= 4 && @fields <= 5;
    s/^\s+|\s+$//g for @fields;
    die "Malformed SpecialCasing source '$raw_line'\n" unless $fields[0] =~ /^[0-9A-F]{4,6}$/;
    for my $index (1 .. 3) {
        die "Malformed SpecialCasing mapping '$raw_line'\n"
            unless $fields[$index] =~ /\A(?:[0-9A-F]+(?: [0-9A-F]+)*)?\z/;
    }
    $special_records++;
    $conditional_records++ if defined $fields[4] && length $fields[4];
    my @names = qw(lower title upper);
    for my $index (1 .. 3) {
        $special_multi{$names[$index - 1]}++ if split(/ /, $fields[$index]) > 1;
    }
}
die "Expected 119 SpecialCasing records, found $special_records\n"
    unless $special_records == 119;
die "Expected 16 conditional SpecialCasing records, found $conditional_records\n"
    unless $conditional_records == 16;
die "Unexpected SpecialCasing multi-code-point counts\n"
    unless $special_multi{lower} == 7 && $special_multi{title} == 48
        && $special_multi{upper} == 102;

sub uleb128 {
    my ($value) = @_;
    die "Cannot encode negative ULEB128 value\n" if $value < 0;
    my $bytes = '';
    do {
        my $byte = $value & 0x7f;
        $value >>= 7;
        $byte |= 0x80 if $value;
        $bytes .= pack('C', $byte);
    } while ($value);
    return $bytes;
}

sub encode_delta_list {
    my ($values) = @_;
    my $bytes = uleb128(scalar @$values);
    my $previous = 0;
    for my $value (@$values) {
        $bytes .= uleb128($value - $previous);
        $previous = $value;
    }
    return $bytes;
}

my @full_sources = sort { $a <=> $b } keys %full;
my $full_blob = uleb128(scalar @full_sources);
my $previous_source = 0;
for my $source (@full_sources) {
    $full_blob .= uleb128($source - $previous_source);
    $previous_source = $source;
    $full_blob .= uleb128(scalar @{$full{$source}});
    $full_blob .= uleb128($_) for @{$full{$source}};
}

my $simple_blob = uleb128(scalar @simple_classes);
for my $class (@simple_classes) {
    $simple_blob .= uleb128(scalar @$class);
    my $previous = 0;
    for my $member (@$class) {
        $simple_blob .= uleb128($member - $previous);
        $previous = $member;
    }
}

sub compare_sequences {
    my ($left, $right) = @_;
    my @left = split /,/, $left;
    my @right = split /,/, $right;
    for my $index (0 .. (@left < @right ? $#left : $#right)) {
        my $comparison = $left[$index] <=> $right[$index];
        return $comparison if $comparison;
    }
    return @left <=> @right;
}
my @reverse_keys = sort { compare_sequences($a, $b) } keys %reverse;
my $reverse_blob = uleb128(scalar @reverse_keys);
for my $key (@reverse_keys) {
    my @sequence = split /,/, $key;
    $reverse_blob .= uleb128(scalar @sequence);
    $reverse_blob .= uleb128($_) for @sequence;
    my @sources = sort { $a <=> $b } @{$reverse{$key}};
    $reverse_blob .= uleb128(scalar @sources);
    my $previous = 0;
    for my $source (@sources) {
        $reverse_blob .= uleb128($source - $previous);
        $previous = $source;
    }
}

my @components = sort { $a <=> $b } keys %component;
my $component_blob = encode_delta_list(\@components);
@turkic_sources = sort { $a <=> $b } @turkic_sources;
my $turkic_blob = encode_delta_list(\@turkic_sources);

sub base64_literal {
    my ($name, $bytes) = @_;
    my $base64 = encode_base64($bytes, '');
    my @chunks = $base64 =~ /.{1,96}/g;
    print "    private static final String $name =\n";
    for my $index (0 .. $#chunks) {
        my $suffix = $index == $#chunks ? ";" : " +";
        print "            \"$chunks[$index]\"$suffix\n";
    }
    print "\n";
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
 *
 * Unicode data sources:
 * CaseFolding-17.0.0.txt and SpecialCasing-17.0.0.txt
 * © 2025 Unicode®, Inc.
 * Unicode and the Unicode Logo are registered trademarks of Unicode, Inc. in the
 * U.S. and other countries.
 * For terms of use and license, see https://www.unicode.org/terms_of_use.html
 *
 * Perl regex-generator provenance:
 * regcharclass.h: Copyright (C) 2007, 2011 by Larry Wall and others.
 * Distributed under either the GNU General Public License or the Artistic License,
 * as specified by the Perl source-tree README.
 */
package org.joni;

import java.util.Arrays;
import java.util.Base64;

// Generated by dev/tools/generate_perl_unicode_case_fold_data.pl. Do not edit manually.
final class PerlUnicodeCaseFoldData {
HEADER

print "    static final String UNICODE_VERSION = \"$unicode_version\";\n";
print "    static final String CASE_FOLDING_SHA256 = \"$unicode_hash{'CaseFolding.txt'}\";\n";
print "    static final String SPECIAL_CASING_SHA256 = \"$unicode_hash{'SpecialCasing.txt'}\";\n";
print "    static final String PERL_MULTI_FOLD_GENERATOR_SHA256 = \"$perl_hash{'regen/regcharclass_multi_char_folds.pl'}\";\n";
print "    static final String PERL_INVERSION_GENERATOR_SHA256 = \"$perl_hash{'regen/mk_invlists.pl'}\";\n";
print "    static final String PERL_MKTABLES_SHA256 = \"$perl_hash{'lib/unicore/mktables'}\";\n";
print "    static final String PERL_INVERSION_DATA_SHA256 = \"$perl_hash{'charclass_invlists.inc'}\";\n";
print "    static final String PERL_REGCHARCLASS_SHA256 = \"$perl_hash{'regcharclass.h'}\";\n";
print "    static final String PERL_REGCHARCLASS_GENERATOR_SHA256 = \"$perl_hash{'regen/regcharclass.pl'}\";\n\n";
print "    static final int FULL_MAPPING_COUNT = 1585;\n";
print "    static final int FULL_CODE_POINT_COUNT = 1705;\n";
print "    static final int SIMPLE_CLASS_COUNT = 1482;\n";
print "    static final int SIMPLE_MEMBER_COUNT = 2994;\n";
print "    static final int REVERSE_SEQUENCE_COUNT = 73;\n";
print "    static final int REVERSE_SOURCE_COUNT = 104;\n";
print "    static final int MULTI_FOLD_COMPONENT_COUNT = 65;\n";
print "    static final int TURKIC_SOURCE_COUNT = 2;\n\n";

base64_literal('FULL_DATA', $full_blob);
base64_literal('SIMPLE_DATA', $simple_blob);
base64_literal('REVERSE_DATA', $reverse_blob);
base64_literal('COMPONENT_DATA', $component_blob);
base64_literal('TURKIC_DATA', $turkic_blob);

print <<'JAVA';
    private static final int[] FULL_SOURCES;
    private static final int[] FULL_OFFSETS;
    private static final int[] FULL_CODE_POINTS;
    private static final int[] SIMPLE_CLASS_OFFSETS;
    private static final int[] SIMPLE_CLASS_MEMBERS;
    private static final int[] SIMPLE_MEMBER_KEYS;
    private static final int[] SIMPLE_MEMBER_CLASS_IDS;
    private static final int[] REVERSE_SEQUENCE_OFFSETS;
    private static final int[] REVERSE_SEQUENCE_CODE_POINTS;
    private static final int[] REVERSE_SOURCE_OFFSETS;
    private static final int[] REVERSE_SOURCES;
    private static final int[] MULTI_FOLD_COMPONENTS;
    private static final int[] TURKIC_SOURCES;

    static {
        FullTable full = decodeFull(FULL_DATA);
        FULL_SOURCES = full.sources;
        FULL_OFFSETS = full.offsets;
        FULL_CODE_POINTS = full.codePoints;

        SimpleTable simple = decodeSimple(SIMPLE_DATA);
        SIMPLE_CLASS_OFFSETS = simple.offsets;
        SIMPLE_CLASS_MEMBERS = simple.members;
        SIMPLE_MEMBER_KEYS = simple.memberKeys;
        SIMPLE_MEMBER_CLASS_IDS = simple.memberClassIds;

        ReverseTable reverse = decodeReverse(REVERSE_DATA);
        REVERSE_SEQUENCE_OFFSETS = reverse.sequenceOffsets;
        REVERSE_SEQUENCE_CODE_POINTS = reverse.sequenceCodePoints;
        REVERSE_SOURCE_OFFSETS = reverse.sourceOffsets;
        REVERSE_SOURCES = reverse.sources;

        MULTI_FOLD_COMPONENTS = decodeDeltaList(COMPONENT_DATA,
                MULTI_FOLD_COMPONENT_COUNT);
        TURKIC_SOURCES = decodeDeltaList(TURKIC_DATA, TURKIC_SOURCE_COUNT);
    }

    static int fullMappingCount() {
        return FULL_SOURCES.length;
    }

    static int fullSourceAt(int mappingIndex) {
        return FULL_SOURCES[mappingIndex];
    }

    static int fullFoldLength(int source) {
        int mappingIndex = Arrays.binarySearch(FULL_SOURCES, source);
        return mappingIndex < 0 ? 0
                : FULL_OFFSETS[mappingIndex + 1] - FULL_OFFSETS[mappingIndex];
    }

    static int fullFoldCodePoint(int source, int foldIndex) {
        int mappingIndex = Arrays.binarySearch(FULL_SOURCES, source);
        if (mappingIndex < 0) throw new IllegalArgumentException("Unknown full-fold source");
        int start = FULL_OFFSETS[mappingIndex];
        int length = FULL_OFFSETS[mappingIndex + 1] - start;
        if (foldIndex < 0 || foldIndex >= length) throw new IndexOutOfBoundsException(foldIndex);
        return FULL_CODE_POINTS[start + foldIndex];
    }

    static int simpleClassCount() {
        return SIMPLE_CLASS_OFFSETS.length - 1;
    }

    static int simpleClassLengthAt(int classIndex) {
        checkSimpleClassIndex(classIndex);
        return SIMPLE_CLASS_OFFSETS[classIndex + 1] - SIMPLE_CLASS_OFFSETS[classIndex];
    }

    static int simpleClassCodePointAt(int classIndex, int memberIndex) {
        checkSimpleClassIndex(classIndex);
        int start = SIMPLE_CLASS_OFFSETS[classIndex];
        int length = SIMPLE_CLASS_OFFSETS[classIndex + 1] - start;
        if (memberIndex < 0 || memberIndex >= length) throw new IndexOutOfBoundsException(memberIndex);
        return SIMPLE_CLASS_MEMBERS[start + memberIndex];
    }

    static int simpleFoldClassLength(int codePoint) {
        int memberIndex = Arrays.binarySearch(SIMPLE_MEMBER_KEYS, codePoint);
        return memberIndex < 0 ? 0 : simpleClassLengthAt(SIMPLE_MEMBER_CLASS_IDS[memberIndex]);
    }

    static int simpleFoldClassCodePoint(int codePoint, int classMemberIndex) {
        int memberIndex = Arrays.binarySearch(SIMPLE_MEMBER_KEYS, codePoint);
        if (memberIndex < 0) throw new IllegalArgumentException("Unknown simple-fold member");
        return simpleClassCodePointAt(SIMPLE_MEMBER_CLASS_IDS[memberIndex], classMemberIndex);
    }

    static int reverseSequenceCount() {
        return REVERSE_SEQUENCE_OFFSETS.length - 1;
    }

    static int reverseSequenceLengthAt(int sequenceIndex) {
        checkReverseSequenceIndex(sequenceIndex);
        return REVERSE_SEQUENCE_OFFSETS[sequenceIndex + 1]
                - REVERSE_SEQUENCE_OFFSETS[sequenceIndex];
    }

    static int reverseSequenceCodePointAt(int sequenceIndex, int sequenceCodePointIndex) {
        checkReverseSequenceIndex(sequenceIndex);
        int start = REVERSE_SEQUENCE_OFFSETS[sequenceIndex];
        int length = REVERSE_SEQUENCE_OFFSETS[sequenceIndex + 1] - start;
        if (sequenceCodePointIndex < 0 || sequenceCodePointIndex >= length) {
            throw new IndexOutOfBoundsException(sequenceCodePointIndex);
        }
        return REVERSE_SEQUENCE_CODE_POINTS[start + sequenceCodePointIndex];
    }

    static int reverseSourceCountAt(int sequenceIndex) {
        checkReverseSequenceIndex(sequenceIndex);
        return REVERSE_SOURCE_OFFSETS[sequenceIndex + 1]
                - REVERSE_SOURCE_OFFSETS[sequenceIndex];
    }

    static int reverseSourceAt(int sequenceIndex, int sourceIndex) {
        checkReverseSequenceIndex(sequenceIndex);
        int start = REVERSE_SOURCE_OFFSETS[sequenceIndex];
        int length = REVERSE_SOURCE_OFFSETS[sequenceIndex + 1] - start;
        if (sourceIndex < 0 || sourceIndex >= length) throw new IndexOutOfBoundsException(sourceIndex);
        return REVERSE_SOURCES[start + sourceIndex];
    }

    static int reverseFullFoldSourceCount(int[] sequence, int offset, int length) {
        int sequenceIndex = findReverseSequence(sequence, offset, length);
        return sequenceIndex < 0 ? 0 : reverseSourceCountAt(sequenceIndex);
    }

    static int reverseFullFoldSourceAt(int[] sequence, int offset, int length,
                                       int sourceIndex) {
        int sequenceIndex = findReverseSequence(sequence, offset, length);
        if (sequenceIndex < 0) throw new IllegalArgumentException("Unknown reverse full fold");
        return reverseSourceAt(sequenceIndex, sourceIndex);
    }

    static boolean isMultiFoldComponent(int codePoint) {
        return Arrays.binarySearch(MULTI_FOLD_COMPONENTS, codePoint) >= 0;
    }

    static boolean isTurkicSourceExcluded(int codePoint) {
        return Arrays.binarySearch(TURKIC_SOURCES, codePoint) >= 0;
    }

    private static int findReverseSequence(int[] sequence, int offset, int length) {
        if (sequence == null) throw new NullPointerException("sequence");
        if (offset < 0 || length < 0 || offset > sequence.length - length) {
            throw new IndexOutOfBoundsException("Invalid sequence slice");
        }
        int low = 0;
        int high = reverseSequenceCount() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int comparison = compareReverseSequence(middle, sequence, offset, length);
            if (comparison < 0) low = middle + 1;
            else if (comparison > 0) high = middle - 1;
            else return middle;
        }
        return -1;
    }

    private static int compareReverseSequence(int sequenceIndex, int[] candidate,
                                              int offset, int length) {
        int start = REVERSE_SEQUENCE_OFFSETS[sequenceIndex];
        int storedLength = REVERSE_SEQUENCE_OFFSETS[sequenceIndex + 1] - start;
        int common = Math.min(storedLength, length);
        for (int index = 0; index < common; index++) {
            int comparison = Integer.compare(REVERSE_SEQUENCE_CODE_POINTS[start + index],
                    candidate[offset + index]);
            if (comparison != 0) return comparison;
        }
        return Integer.compare(storedLength, length);
    }

    private static void checkSimpleClassIndex(int classIndex) {
        if (classIndex < 0 || classIndex >= simpleClassCount()) {
            throw new IndexOutOfBoundsException(classIndex);
        }
    }

    private static void checkReverseSequenceIndex(int sequenceIndex) {
        if (sequenceIndex < 0 || sequenceIndex >= reverseSequenceCount()) {
            throw new IndexOutOfBoundsException(sequenceIndex);
        }
    }

    private static FullTable decodeFull(String encoded) {
        Cursor cursor = new Cursor(encoded);
        int count = cursor.read();
        if (count != FULL_MAPPING_COUNT) throw new IllegalStateException("Bad full-fold count");
        int[] sources = new int[count];
        int[] offsets = new int[count + 1];
        int[] codePoints = new int[FULL_CODE_POINT_COUNT];
        int source = 0;
        int codePointOffset = 0;
        for (int mappingIndex = 0; mappingIndex < count; mappingIndex++) {
            source += cursor.read();
            sources[mappingIndex] = source;
            offsets[mappingIndex] = codePointOffset;
            int length = cursor.read();
            for (int index = 0; index < length; index++) {
                codePoints[codePointOffset++] = cursor.read();
            }
        }
        offsets[count] = codePointOffset;
        if (codePointOffset != codePoints.length || !cursor.exhausted()) {
            throw new IllegalStateException("Bad full-fold payload");
        }
        return new FullTable(sources, offsets, codePoints);
    }

    private static SimpleTable decodeSimple(String encoded) {
        Cursor cursor = new Cursor(encoded);
        int count = cursor.read();
        if (count != SIMPLE_CLASS_COUNT) throw new IllegalStateException("Bad simple-fold count");
        int[] offsets = new int[count + 1];
        int[] members = new int[SIMPLE_MEMBER_COUNT];
        long[] memberships = new long[SIMPLE_MEMBER_COUNT];
        int memberOffset = 0;
        for (int classIndex = 0; classIndex < count; classIndex++) {
            offsets[classIndex] = memberOffset;
            int length = cursor.read();
            int member = 0;
            for (int index = 0; index < length; index++) {
                member += cursor.read();
                members[memberOffset] = member;
                memberships[memberOffset] = ((long) member << 32) | (classIndex & 0xffffffffL);
                memberOffset++;
            }
        }
        offsets[count] = memberOffset;
        if (memberOffset != members.length || !cursor.exhausted()) {
            throw new IllegalStateException("Bad simple-fold payload");
        }
        Arrays.sort(memberships);
        int[] memberKeys = new int[memberships.length];
        int[] memberClassIds = new int[memberships.length];
        for (int index = 0; index < memberships.length; index++) {
            memberKeys[index] = (int) (memberships[index] >>> 32);
            memberClassIds[index] = (int) memberships[index];
        }
        return new SimpleTable(offsets, members, memberKeys, memberClassIds);
    }

    private static ReverseTable decodeReverse(String encoded) {
        Cursor cursor = new Cursor(encoded);
        int count = cursor.read();
        if (count != REVERSE_SEQUENCE_COUNT) throw new IllegalStateException("Bad reverse-fold count");
        int[] sequenceOffsets = new int[count + 1];
        int[] sequenceCodePoints = new int[160];
        int[] sourceOffsets = new int[count + 1];
        int[] sources = new int[REVERSE_SOURCE_COUNT];
        int sequenceOffset = 0;
        int sourceOffset = 0;
        for (int sequenceIndex = 0; sequenceIndex < count; sequenceIndex++) {
            sequenceOffsets[sequenceIndex] = sequenceOffset;
            int length = cursor.read();
            for (int index = 0; index < length; index++) {
                sequenceCodePoints[sequenceOffset++] = cursor.read();
            }
            sourceOffsets[sequenceIndex] = sourceOffset;
            int sourceCount = cursor.read();
            int source = 0;
            for (int index = 0; index < sourceCount; index++) {
                source += cursor.read();
                sources[sourceOffset++] = source;
            }
        }
        sequenceOffsets[count] = sequenceOffset;
        sourceOffsets[count] = sourceOffset;
        if (sequenceOffset != sequenceCodePoints.length || sourceOffset != sources.length
                || !cursor.exhausted()) {
            throw new IllegalStateException("Bad reverse-fold payload");
        }
        return new ReverseTable(sequenceOffsets, sequenceCodePoints, sourceOffsets, sources);
    }

    private static int[] decodeDeltaList(String encoded, int expectedCount) {
        Cursor cursor = new Cursor(encoded);
        int count = cursor.read();
        if (count != expectedCount) throw new IllegalStateException("Bad delta-list count");
        int[] values = new int[count];
        int value = 0;
        for (int index = 0; index < count; index++) {
            value += cursor.read();
            values[index] = value;
        }
        if (!cursor.exhausted()) throw new IllegalStateException("Bad delta-list payload");
        return values;
    }

    private static final class Cursor {
        private final byte[] bytes;
        private int offset;

        Cursor(String encoded) {
            bytes = Base64.getDecoder().decode(encoded);
        }

        int read() {
            int value = 0;
            int shift = 0;
            while (true) {
                if (offset >= bytes.length || shift > 28) {
                    throw new IllegalStateException("Malformed ULEB128 payload");
                }
                int next = bytes[offset++] & 0xff;
                value |= (next & 0x7f) << shift;
                if ((next & 0x80) == 0) return value;
                shift += 7;
            }
        }

        boolean exhausted() {
            return offset == bytes.length;
        }
    }

    private static final class FullTable {
        final int[] sources;
        final int[] offsets;
        final int[] codePoints;

        FullTable(int[] sources, int[] offsets, int[] codePoints) {
            this.sources = sources;
            this.offsets = offsets;
            this.codePoints = codePoints;
        }
    }

    private static final class SimpleTable {
        final int[] offsets;
        final int[] members;
        final int[] memberKeys;
        final int[] memberClassIds;

        SimpleTable(int[] offsets, int[] members, int[] memberKeys, int[] memberClassIds) {
            this.offsets = offsets;
            this.members = members;
            this.memberKeys = memberKeys;
            this.memberClassIds = memberClassIds;
        }
    }

    private static final class ReverseTable {
        final int[] sequenceOffsets;
        final int[] sequenceCodePoints;
        final int[] sourceOffsets;
        final int[] sources;

        ReverseTable(int[] sequenceOffsets, int[] sequenceCodePoints,
                     int[] sourceOffsets, int[] sources) {
            this.sequenceOffsets = sequenceOffsets;
            this.sequenceCodePoints = sequenceCodePoints;
            this.sourceOffsets = sourceOffsets;
            this.sources = sources;
        }
    }

    private PerlUnicodeCaseFoldData() {
    }
}
JAVA
