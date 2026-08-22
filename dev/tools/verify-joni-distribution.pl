#!/usr/bin/env perl

use strict;
use warnings;

use Cwd qw(abs_path);
use File::Find qw(find);
use File::Spec;
use FindBin qw($Bin);

die "Usage: $0 <install-directory>\n" unless @ARGV == 1;
my $distribution = abs_path($ARGV[0]);
die "Installed distribution is missing: $ARGV[0]\n"
    unless defined $distribution && -d $distribution;

my $root = abs_path(File::Spec->catdir($Bin, '..', '..'))
    or die "Cannot resolve repository root from $Bin\n";
my $lib = File::Spec->catdir($distribution, 'lib');
die "Installed distribution has no lib directory\n" unless -d $lib;

opendir my $lib_fh, $lib or die "Cannot open $lib: $!\n";
my @jars = sort map { File::Spec->catfile($lib, $_) }
    grep { /\.jar\z/ && -f File::Spec->catfile($lib, $_) } readdir $lib_fh;
closedir $lib_fh or die "Cannot close $lib: $!\n";
die "Installed distribution must contain exactly one runtime JAR (found "
    . scalar(@jars) . ")\n" unless @jars == 1;
my $jar = $jars[0];

my %entries;
open my $jar_fh, '-|', 'jar', 'tf', $jar
    or die "Cannot list $jar: $!\n";
while (my $entry = <$jar_fh>) {
    chomp $entry;
    $entries{$entry}++;
}
close $jar_fh or die "Cannot list $jar: jar exited with status $?\n";

for my $entry (sort keys %entries) {
    next unless $entry =~ /\.class\z/;
    die "Runtime JAR contains duplicate class entry $entry\n"
        unless $entries{$entry} == 1;
}
die "Runtime JAR contains unrelocated Joni classes\n"
    if grep { m{^org/joni/.*\.class\z} } keys %entries;
die "Runtime JAR contains unrelocated JCodings classes\n"
    if grep { m{^org/jcodings/.*\.class\z} } keys %entries;
die "Runtime JAR does not contain relocated Joni classes\n"
    unless grep { m{^org/perlonjava/internal/joni/.*\.class\z} } keys %entries;
die "Runtime JAR does not contain relocated JCodings classes\n"
    unless grep { m{^org/perlonjava/internal/jcodings/.*\.class\z} } keys %entries;

my @loose_unrelocated;
find({
    no_chdir => 1,
    wanted => sub {
        return unless -f $_;
        my $relative = File::Spec->abs2rel($_, $distribution);
        $relative =~ s{\\}{/}g;
        push @loose_unrelocated, $relative
            if $relative =~ m{(?:^|/)org/(?:joni|jcodings)/.*\.class\z};
    },
}, $distribution);
die "Installed distribution contains unrelocated loose class $loose_unrelocated[0]\n"
    if @loose_unrelocated;

my %notices = (
    'joni-LICENSE.txt' => File::Spec->catfile($root, 'third_party', 'joni', 'LICENSE'),
    'joni-PERLONJAVA-NOTICE.md' => File::Spec->catfile(
        $root, 'third_party', 'joni', 'PERLONJAVA-NOTICE.md'),
    'jcodings-LICENSE.txt' => File::Spec->catfile(
        $root, 'third_party', 'licenses', 'jcodings-LICENSE.txt'),
);
my $license_directory = File::Spec->catdir($distribution, 'share', 'licenses');
die "Installed distribution has no stable share/licenses directory\n"
    unless -d $license_directory;
for my $name (sort keys %notices) {
    my $installed = File::Spec->catfile($license_directory, $name);
    die "Installed distribution is missing $name\n" unless -f $installed;
    die "Installed distribution notice bytes differ for $name\n"
        unless read_raw($installed) eq read_raw($notices{$name});
}

my $jar_name = (File::Spec->splitpath($jar))[2];
for my $launcher ('perlonjava', 'perlonjava.bat') {
    my $file = File::Spec->catfile($distribution, 'bin', $launcher);
    die "Installed distribution is missing launcher $launcher\n" unless -f $file;
    my $contents = read_raw($file);
    my @classpath_jars = $contents =~ m{lib[\\/]([^\s"':;%]+\.jar)}g;
    my %classpath_jars = map { $_ => 1 } @classpath_jars;
    die "Installed launcher $launcher does not select $jar_name\n"
        unless $classpath_jars{$jar_name};
    delete $classpath_jars{$jar_name};
    die "Installed launcher $launcher references additional runtime JARs: "
        . join(', ', sort keys %classpath_jars) . "\n"
        if %classpath_jars;
}

print "Joni distribution relocation verification passed: $jar\n";

sub read_raw {
    my ($file) = @_;
    open my $fh, '<:raw', $file or die "Cannot read $file: $!\n";
    my $contents = do { local $/; <$fh> };
    close $fh or die "Cannot close $file: $!\n";
    return $contents;
}
