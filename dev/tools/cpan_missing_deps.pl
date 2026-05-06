#!/usr/bin/env perl
use strict;
use warnings;

# Analyze cpan-compatibility-fail.dat and report top N missing dependencies.
# Usage: perl dev/tools/cpan_missing_deps.pl [--top N] [--dist] [dat-file ...]
#   --top N   show top N entries (default: 10)
#   --dist    collapse to top-level distribution (first two namespace components)

my $top   = 10;
my $dist  = 0;
my @files;

while (@ARGV) {
    my $arg = shift @ARGV;
    if ($arg eq '--top')  { $top  = shift @ARGV }
    elsif ($arg eq '--dist') { $dist = 1 }
    else                     { push @files, $arg }
}

unless (@files) {
    # Default: look relative to this script's location
    my ($script_dir) = __FILE__ =~ m{^(.*)/};
    my $default = "$script_dir/../cpan-reports/cpan-compatibility-fail.dat";
    push @files, $default;
}

my %count;   # missing module => count of CPAN modules that need it
my %users;   # missing module => list of CPAN modules that need it

for my $file (@files) {
    open my $fh, '<', $file or die "Cannot open $file: $!";
    while (my $line = <$fh>) {
        chomp $line;
        # Format: Module\tFAIL\t...\tMissing: Path/To/Module.pm\t...
        next unless $line =~ /\tMissing:\s+(.+?)(?:\t|$)/;
        my $raw = $1;

        # Convert path/to/Module.pm  ->  path::to::Module
        (my $mod = $raw) =~ s{/}{::}g;
        $mod =~ s/\.pm$//i;

        if ($dist) {
            # Collapse to first two components (rough distribution approximation)
            my @parts = split /::/, $mod;
            $mod = join '::', @parts[0 .. ($#parts > 0 ? 1 : 0)];
        }

        my $cpan_mod = (split /\t/, $line)[0];
        $count{$mod}++;
        push @{ $users{$mod} }, $cpan_mod;
    }
    close $fh;
}

# Sort by count descending, then alphabetically
my @sorted = sort { $count{$b} <=> $count{$a} || $a cmp $b } keys %count;

printf "%-55s  %5s  %s\n", "Missing dependency", "Count", "Example users";
printf "%s\n", "-" x 100;
my $shown = 0;
for my $mod (@sorted) {
    last if $shown++ >= $top;
    my @ex = @{ $users{$mod} };
    my $examples = join(', ', @ex[0 .. ($#ex > 2 ? 2 : $#ex)]);
    $examples .= ', ...' if @ex > 3;
    printf "%-55s  %5d  %s\n", $mod, $count{$mod}, $examples;
}

print "\nTotal entries with Missing: dependency: ", scalar(grep { /\tMissing:/ } map { $_ } do {
    my @lines;
    for my $f (@files) {
        open my $fh2, '<', $f or die $!;
        push @lines, <$fh2>;
        close $fh2;
    }
    @lines;
}), "\n";
