#!/usr/bin/env perl
use strict;
use warnings;
use Digest::SHA qw(sha256_hex);
use File::Spec;
use FindBin;

my $expected_version = '17.0.0';
my $root = File::Spec->catdir($FindBin::Bin, '..', '..');
my $unicore = File::Spec->catdir($root, 'perl5', 'lib', 'unicore');
my @properties = (
    { key => 'bpt', long => 'Bidi_Paired_Bracket_Type', default => 'None', parser => 'bpt',
      source_name => 'BidiBrackets-17.0.0.txt', path => File::Spec->catfile($unicore, 'BidiBrackets.txt'),
      hash => 'dadbaf38a0d0246e5b805bf8725cb81b7c621f93d030595635f5ba2c2f179428',
      version => qr/^# BidiBrackets-\Q$expected_version\E\.txt$/m },
    { key => 'InCB', long => 'Indic_Conjunct_Break', default => 'None', parser => 'incb',
      source_name => 'DerivedCoreProperties-17.0.0.txt', path => File::Spec->catfile($unicore, 'DCoreProperties.txt'),
      hash => '24c7fed1195c482faaefd5c1e7eb821c5ee1fb6de07ecdbaa64b56a99da22c08',
      version => qr/^# DerivedCoreProperties-\Q$expected_version\E\.txt$/m },
    { key => 'jt', long => 'Joining_Type', default => 'Non_Joining', parser => 'simple',
      source_name => 'DerivedJoiningType-17.0.0.txt', path => File::Spec->catfile($unicore, 'extracted', 'DJoinType.txt'),
      hash => 'f39ebe974825d6736aee15582250307aa532b2cfab3caf3f86bd23fddc9c5c4d',
      version => qr/^# DerivedJoiningType-\Q$expected_version\E\.txt$/m },
    { key => 'nt', long => 'Numeric_Type', default => 'None', parser => 'simple',
      source_name => 'DerivedNumericType-17.0.0.txt', path => File::Spec->catfile($unicore, 'extracted', 'DNumType.txt'),
      hash => '7c83684d3336b698381745b78a971c3e1242cb3fcac58604469086c19b6edcee',
      version => qr/^# DerivedNumericType-\Q$expected_version\E\.txt$/m },
    { key => 'vo', long => 'Vertical_Orientation', default => 'Rotated', parser => 'simple',
      source_name => 'VerticalOrientation-17.0.0.txt', path => File::Spec->catfile($unicore, 'VerticalOrientation.txt'),
      hash => 'dcef09c3fb24d356b042569c328ec341efc5b53447700d799f2fb4834c3cd3cd',
      version => qr/^# VerticalOrientation-\Q$expected_version\E\.txt$/m },
);
my @metadata = (
    { source_name => 'PropertyValueAliases-17.0.0.txt', path => File::Spec->catfile($unicore, 'PropValueAliases.txt'),
      hash => '670d2bebb48649c04fabfbf033308073dcff47946324a8033237254c048b3b01',
      version => qr/^# PropertyValueAliases-\Q$expected_version\E\.txt$/m },
    { source_name => 'PropertyAliases-17.0.0.txt', path => File::Spec->catfile($unicore, 'PropertyAliases.txt'),
      hash => '4441f573caf952ffece1d7c892e7715bd7136dfc26f96eb6f268bf1e474715fb',
      version => qr/^# PropertyAliases-\Q$expected_version\E\.txt$/m },
);

sub read_source {
    my ($s) = @_;
    open my $fh, '<:raw', $s->{path} or die "Cannot read $s->{path}: $!\n";
    local $/; my $text = <$fh>; close $fh or die "Cannot close $s->{path}: $!\n";
    my $actual = sha256_hex($text);
    die "$s->{path} SHA-256 mismatch: expected $s->{hash}, found $actual\n" unless $actual eq $s->{hash};
    die "$s->{path} is not pinned Unicode $expected_version data\n" unless $text =~ $s->{version};
    $s->{text} = $text;
}
sub trim { my $x = shift; $x =~ s/^\s+|\s+$//g; $x }
sub loose { my $x = lc shift; $x =~ s/[\s_-]+//g; $x }
sub parse_range { my ($x) = @_; my ($a, $b) = split /\.\./, $x; (hex($a), hex($b // $a)) }
read_source($_) for @properties, @metadata;
open my $vf, '<', File::Spec->catfile($unicore, 'version') or die $!;
chomp(my $version = <$vf>); close $vf; die "Expected Unicode $expected_version, found $version\n" unless $version eq $expected_version;

my %by_key = map { $_->{key} => $_ } @properties;
for my $p (@properties) { $p->{short_values}=[]; $p->{long_values}=[]; $p->{alias_index}={}; $p->{wildcards}={}; $p->{property_aliases}={} }
for my $line (split /\n/, $metadata[0]{text}) {
    next if $line =~ /^\s*#/; $line =~ s/#.*$//; my @f = map { trim($_) } split /;/, $line;
    next unless @f >= 3 && exists $by_key{$f[0]}; my $p = $by_key{$f[0]}; my $i = @{$p->{short_values}};
    push @{$p->{short_values}}, $f[1]; push @{$p->{long_values}}, $f[2];
    for my $a (@f[1..$#f]) { next unless length $a; my $l=loose($a); die "Alias collision $a\n" if exists $p->{alias_index}{$l} && $p->{alias_index}{$l}!=$i; $p->{alias_index}{$l}=$i; $p->{wildcards}{$a}=$i }
}
for my $line (split /\n/, $metadata[1]{text}) {
    next if $line =~ /^\s*#/; $line =~ s/#.*$//; my @f=map {trim($_)} split /;/,$line; next unless @f>=2;
    for my $p (@properties) { next unless grep {$_ eq $p->{key} || $_ eq $p->{long}} @f; $p->{property_aliases}{loose($_)}=1 for grep {length} @f }
}
my %expected=(bpt=>3,InCB=>4,jt=>6,nt=>4,vo=>4);
for my $p (@properties) {
    die "Expected $expected{$p->{key}} values for $p->{key}\n" unless @{$p->{long_values}}==$expected{$p->{key}};
    my (@missing,@explicit);
    if ($p->{parser} eq 'bpt') {
        my $idx=$p->{alias_index}{loose($p->{default})}; push @missing,[0,0x10ffff,$idx];
        for my $line (split /\n/,$p->{text}) { next unless $line =~ /^([0-9A-F]+)\s*;\s*[^;]+;\s*([ocn])\b/; my $i=$p->{alias_index}{loose($2)}; push @explicit,[hex($1),hex($1),$i] }
    } elsif ($p->{parser} eq 'incb') {
        for my $line (split /\n/,$p->{text}) {
            if ($line =~ /^#\s*\@missing:\s*([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*InCB\s*;\s*([A-Za-z_]+)/) { my($r,$v)=($1,$2); my($a,$b)=parse_range($r); push @missing,[$a,$b,$p->{alias_index}{loose($v)}]; next }
            next unless $line =~ /^([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*InCB\s*;\s*([A-Za-z_]+)/; my($r,$v)=($1,$2); my($a,$b)=parse_range($r); push @explicit,[$a,$b,$p->{alias_index}{loose($v)}];
        }
    } else {
        for my $line (split /\n/,$p->{text}) {
            if ($line =~ /^#\s*\@missing:\s*([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*([A-Za-z_]+)/) { my($r,$v)=($1,$2); my($a,$b)=parse_range($r); push @missing,[$a,$b,$p->{alias_index}{loose($v)}]; next }
            next unless $line =~ /^([0-9A-F]+(?:\.\.[0-9A-F]+)?)\s*;\s*([A-Za-z_]+)/; my($r,$v)=($1,$2); my($a,$b)=parse_range($r); push @explicit,[$a,$b,$p->{alias_index}{loose($v)}];
        }
    }
    die "Missing data for $p->{key}\n" unless @missing && @explicit;
    $p->{missing_count}=scalar @missing; $p->{explicit_count}=scalar @explicit;
    my $d=$p->{alias_index}{loose($p->{default})}; my @values=($d)x0x110000;
    for my $r (@missing,@explicit) { my($c,$e,$i)=@$r; die "Unknown value in $p->{key}\n" unless defined $i; $values[$c++]=$i while $c<=$e }
    my @ranges; my($start,$value)=(0,$values[0]); for my $c (1..0x10ffff) { next if $values[$c]==$value; push @ranges,[$start,$c-1,$value]; ($start,$value)=($c,$values[$c]) } push @ranges,[$start,0x10ffff,$value]; $p->{ranges}=\@ranges;
}

print "/*\n * Generated from Perl 5.44's pinned Unicode Character Database. Do not edit manually.\n *\n";
for my $s (@properties,@metadata) { print " * Source: $s->{source_name}\n"; for my $line (split /\n/,$s->{text}) { next unless $line =~ /^# (?:©|Unicode and|the U\.S\.|For terms of use and license|This file was originally created)/; $line =~ s/^# / * /; print "$line\n" } print " *\n" }
print " */\npackage org.perlonjava.runtime.regex;\n\nimport com.ibm.icu.text.UnicodeSet;\n\nfinal class PerlUnicodeEnumeratedData {\n";
print "    static final String UNICODE_VERSION = \"$version\";\n";
for my $p (@properties) { my $c=uc($p->{key}); print "    static final String ${c}_SHA256 = \"$p->{hash}\";\n    static final int ${c}_MISSING_COUNT = $p->{missing_count};\n    static final int ${c}_EXPLICIT_RANGE_COUNT = $p->{explicit_count};\n" }
print "    static final String PROP_VALUE_ALIASES_SHA256 = \"$metadata[0]{hash}\";\n    static final String PROPERTY_ALIASES_SHA256 = \"$metadata[1]{hash}\";\n\n";
sub strings { my($name,$a)=@_; print "    private static final String[] $name = { ",join(', ',map{qq{\"$_\"}}@$a)," };\n" }
for my $p (@properties) {
    my $c=uc($p->{key}); strings("${c}_SHORT_VALUES",$p->{short_values}); strings("${c}_LONG_VALUES",$p->{long_values});
    my @a=sort keys %{$p->{alias_index}}; strings("${c}_VALUE_ALIASES",\@a); print "    private static final short[] ${c}_VALUE_ALIAS_INDEX = { ",join(', ',map{$p->{alias_index}{$_}}@a)," };\n";
    my @w=sort keys %{$p->{wildcards}}; strings("${c}_WILDCARD_VALUES",\@w); my @pa=sort keys %{$p->{property_aliases}}; strings("${c}_PROPERTY_ALIASES",\@pa);
    my $ranges=$p->{ranges}; print "    private static final int[] ${c}_RANGES = {\n"; for(my $i=0;$i<@$ranges;$i+=4){my $e=$i+3<$#$ranges?$i+3:$#$ranges; print "        ",join(', ',map{sprintf '0x%X, 0x%X, %d',@{$ranges->[$_]}[0,1,2]}$i..$e),",\n"} print "    };\n";
}
print <<'JAVA';
    private static final String[][] SHORT_VALUES = { BPT_SHORT_VALUES, INCB_SHORT_VALUES, JT_SHORT_VALUES, NT_SHORT_VALUES, VO_SHORT_VALUES };
    private static final String[][] LONG_VALUES = { BPT_LONG_VALUES, INCB_LONG_VALUES, JT_LONG_VALUES, NT_LONG_VALUES, VO_LONG_VALUES };
    private static final String[][] VALUE_ALIASES = { BPT_VALUE_ALIASES, INCB_VALUE_ALIASES, JT_VALUE_ALIASES, NT_VALUE_ALIASES, VO_VALUE_ALIASES };
    private static final short[][] VALUE_ALIAS_INDEX = { BPT_VALUE_ALIAS_INDEX, INCB_VALUE_ALIAS_INDEX, JT_VALUE_ALIAS_INDEX, NT_VALUE_ALIAS_INDEX, VO_VALUE_ALIAS_INDEX };
    private static final String[][] WILDCARD_VALUES = { BPT_WILDCARD_VALUES, INCB_WILDCARD_VALUES, JT_WILDCARD_VALUES, NT_WILDCARD_VALUES, VO_WILDCARD_VALUES };
    private static final String[][] PROPERTY_ALIASES = { BPT_PROPERTY_ALIASES, INCB_PROPERTY_ALIASES, JT_PROPERTY_ALIASES, NT_PROPERTY_ALIASES, VO_PROPERTY_ALIASES };
    private static final int[][] RANGES = { BPT_RANGES, INCB_RANGES, JT_RANGES, NT_RANGES, VO_RANGES };
    private static final UnicodeSet[][] SETS = buildSets();
    static boolean isPropertyAlias(String a) { return propertyIndex(a)>=0; }
    static UnicodeSet valueSet(String p,String v) { int pi=propertyIndex(p); if(pi<0)return null; int vi=valueIndex(pi,v); return vi<0?null:SETS[pi][vi]; }
    static String shortValue(String p,String v) { int pi=propertyIndex(p); if(pi<0)return null; int vi=valueIndex(pi,v); return vi<0?null:SHORT_VALUES[pi][vi]; }
    static String canonicalValue(String p,String v) { int pi=propertyIndex(p); if(pi<0)return null; int vi=valueIndex(pi,v); return vi<0?null:LONG_VALUES[pi][vi]; }
    static String[] canonicalValues(String p) { int i=propertyIndex(p); return i<0?null:LONG_VALUES[i].clone(); }
    static String[] wildcardValues(String p) { int i=propertyIndex(p); return i<0?null:WILDCARD_VALUES[i].clone(); }
    private static int propertyIndex(String a) { String l=loose(a); if(l==null)return -1; for(int p=0;p<PROPERTY_ALIASES.length;p++)for(String c:PROPERTY_ALIASES[p])if(c.equals(l))return p; return -1; }
    private static int valueIndex(int p,String a) { String l=loose(a); if(l==null)return -1; for(int i=0;i<VALUE_ALIASES[p].length;i++)if(VALUE_ALIASES[p][i].equals(l))return VALUE_ALIAS_INDEX[p][i]; return -1; }
    private static String loose(String s) { if(s==null)return null; StringBuilder b=new StringBuilder(); for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='_'||c=='-'||Character.isWhitespace(c))continue;b.append(Character.toLowerCase(c));}return b.toString(); }
    private static UnicodeSet[][] buildSets() { UnicodeSet[][] s=new UnicodeSet[LONG_VALUES.length][]; for(int p=0;p<s.length;p++){s[p]=new UnicodeSet[LONG_VALUES[p].length];for(int v=0;v<s[p].length;v++)s[p][v]=new UnicodeSet();for(int i=0;i<RANGES[p].length;i+=3)s[p][RANGES[p][i+2]].add(RANGES[p][i],RANGES[p][i+1]);for(UnicodeSet set:s[p])set.freeze();}return s; }
    private PerlUnicodeEnumeratedData() {}
}
JAVA
