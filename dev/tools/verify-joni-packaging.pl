#!/usr/bin/env perl

use strict;
use warnings;
use JSON::PP;

die "Usage: $0 <standalone.jar> <sbom.json>\n" unless @ARGV == 2;
my ($jar_file, $sbom_file) = @ARGV;

my %entries;
open my $jar_fh, '-|', 'jar', 'tf', $jar_file
    or die "Cannot list $jar_file: $!\n";
while (my $entry = <$jar_fh>) {
    chomp $entry;
    $entries{$entry} = 1;
}
close $jar_fh or die "Cannot list $jar_file: jar exited with status $?\n";

die "Standalone JAR contains unrelocated Joni classes\n"
    if grep { m{^org/joni/} } keys %entries;
die "Standalone JAR contains unrelocated JCodings classes\n"
    if grep { m{^org/jcodings/} } keys %entries;
die "Standalone JAR does not contain relocated Joni classes\n"
    unless grep { m{^org/perlonjava/internal/joni/} } keys %entries;
die "Standalone JAR does not contain relocated JCodings classes\n"
    unless grep { m{^org/perlonjava/internal/jcodings/} } keys %entries;

for my $notice (qw(
    joni-LICENSE.txt
    jcodings-LICENSE.txt
    joni-PERLONJAVA-NOTICE.md
)) {
    die "Standalone JAR is missing $notice\n"
        unless $entries{"META-INF/licenses/$notice"};
}

open my $sbom_fh, '<', $sbom_file or die "Cannot open $sbom_file: $!\n";
local $/;
my $sbom = JSON::PP->new->utf8->decode(<$sbom_fh>);
close $sbom_fh;

my ($joni) = grep {
    ($_->{group} // '') eq 'org.jruby.joni'
        && ($_->{name} // '') eq 'joni'
        && ($_->{version} // '') eq '2.2.7'
} @{$sbom->{components} // []};
die "Combined SBOM is missing vendored Joni 2.2.7\n" unless $joni;

print "Joni packaging verification passed\n";
