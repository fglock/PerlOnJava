#!/usr/bin/env perl
use strict;
use warnings;
use Time::HiRes qw(time);
use Cwd qw(abs_path);
use File::Basename qw(dirname);
use File::Spec;

# Find repo root
sub find_repo_root {
    my $dir = abs_path(dirname($0));
    while (1) {
        my $jperl = File::Spec->catfile($dir, 'jperl');
        my $git = File::Spec->catdir($dir, '.git');
        if (-f $jperl && -d $git) {
            return $dir;
        }
        my $parent = abs_path(File::Spec->catdir($dir, File::Spec->updir()));
        last if !defined($parent) || $parent eq $dir;
        $dir = $parent;
    }
    die "Unable to locate repo root\n";
}

my $repo_root = find_repo_root();
my $jperl = File::Spec->catfile($repo_root, 'jperl');

# Memory benchmark workloads with delta measurement
my @workloads = (
    {
        name => "Array creation (1M elements)",
        code_before => 'use Devel::Peek; print "READY\n"; my $line = <STDIN>;',
        code_create => 'my @arr = (1..1_000_000);',
        code_after => 'my $sum = 0; $sum += $_ for @arr; print $sum, "\n";',
    },
    {
        name => "Hash creation (100K entries)",
        code_before => 'print "READY\n"; my $line = <STDIN>;',
        code_create => 'my %hash; $hash{$_} = $_ * 2 for (1..100_000);',
        code_after => 'my $sum = 0; $sum += $hash{$_} for keys %hash; print $sum, "\n";',
    },
    {
        name => "String operations (10K iterations)",
        code_before => 'print "READY\n"; my $line = <STDIN>;',
        code_create => 'my $str = "x" x 1000; my $result = ""; for (1..10_000) { $result .= substr($str, 0, 10); }',
        code_after => 'print length($result), "\n";',
    },
    {
        name => "Nested data structures",
        code_before => 'print "READY\n"; my $line = <STDIN>;',
        code_create => 'my @data; for my $i (1..1000) { push @data, { id => $i, values => [1..$i] }; }',
        code_after => 'my $sum = 0; for my $item (@data) { $sum += scalar(@{$item->{values}}); } print $sum, "\n";',
    },
);

print "# Memory Usage Benchmark: perl vs jperl\n\n";
print "Measuring peak memory usage (RSS) for various workloads.\n";
print "Using /usr/bin/time to capture memory statistics.\n\n";

# Check if /usr/bin/time exists
if (!-x '/usr/bin/time') {
    die "Error: /usr/bin/time not found. This script requires GNU time or BSD time.\n";
}

# Detect time format (GNU vs BSD)
my $time_format;
my $time_test = `/usr/bin/time -l echo test 2>&1`;
if ($time_test =~ /maximum resident set size/) {
    # BSD time (macOS)
    $time_format = 'bsd';
} else {
    # Try GNU time
    $time_test = `/usr/bin/time -v echo test 2>&1`;
    if ($time_test =~ /Maximum resident set size/) {
        $time_format = 'gnu';
    } else {
        die "Error: Unable to determine time format. Need GNU time or BSD time.\n";
    }
}

print "Detected time format: $time_format\n\n";

sub get_memory_usage {
    my ($interpreter, $code) = @_;
    
    # Write code to a temp file to avoid shell quoting issues
    my $tmpfile = "/tmp/perlbench_$$.pl";
    my $timefile = "/tmp/perlbench_time_$$.txt";
    
    open my $fh, '>', $tmpfile or die "Cannot write to $tmpfile: $!";
    print $fh $code;
    close $fh;
    
    # Use shell to redirect time output to a file
    my $cmd;
    if ($time_format eq 'bsd') {
        # BSD time: redirect stderr to file
        $cmd = "/usr/bin/time -l $interpreter $tmpfile > /dev/null 2> $timefile";
    } else {
        # GNU time
        $cmd = "/usr/bin/time -v $interpreter $tmpfile > /dev/null 2> $timefile";
    }
    
    system($cmd);
    
    # Read the time output
    open my $tfh, '<', $timefile or do {
        unlink $tmpfile;
        unlink $timefile;
        return undef;
    };
    my $output = do { local $/; <$tfh> };
    close $tfh;
    
    unlink $tmpfile;
    unlink $timefile;
    
    if ($time_format eq 'bsd') {
        # Match format: "        1228800  maximum resident set size"
        if ($output =~ /^\s*(\d+)\s+maximum resident set size/m) {
            # BSD reports in bytes
            return int($1 / 1024); # Convert to KB
        }
    } else {
        # GNU time
        if ($output =~ /Maximum resident set size \(kbytes\): (\d+)/) {
            return $1;
        }
    }
    
    return undef;
}

sub format_memory {
    my ($kb) = @_;
    return "N/A" unless defined $kb;
    
    if ($kb < 1024) {
        return sprintf("%d KB", $kb);
    } elsif ($kb < 1024 * 1024) {
        return sprintf("%.1f MB", $kb / 1024);
    } else {
        return sprintf("%.2f GB", $kb / (1024 * 1024));
    }
}

sub format_ratio {
    my ($perl_mem, $jperl_mem) = @_;
    return "N/A" unless defined $perl_mem && defined $jperl_mem && $perl_mem > 0;
    
    my $ratio = $jperl_mem / $perl_mem;
    return sprintf("%.2fx", $ratio);
}

# Run benchmarks
my @results;

for my $workload (@workloads) {
    print "Running: $workload->{name}\n";
    
    my $code = $workload->{code};
    
    # Run with perl
    my $perl_mem = get_memory_usage('perl', $code);
    print "  perl:  " . format_memory($perl_mem) . "\n";
    
    # Run with jperl
    my $jperl_mem = get_memory_usage($jperl, $code);
    print "  jperl: " . format_memory($jperl_mem) . "\n";
    
    my $ratio = format_ratio($perl_mem, $jperl_mem);
    print "  ratio: $ratio\n\n";
    
    push @results, {
        name => $workload->{name},
        perl_mem => $perl_mem,
        jperl_mem => $jperl_mem,
        ratio => $ratio,
    };
}

# Print summary table
print "\n# Summary\n\n";
print "| Workload | Perl 5 | PerlOnJava | Ratio (jperl/perl) |\n";
print "|----------|--------|------------|--------------------|\n";

for my $result (@results) {
    printf "| %-40s | %10s | %10s | %10s |\n",
        $result->{name},
        format_memory($result->{perl_mem}),
        format_memory($result->{jperl_mem}),
        $result->{ratio};
}

print "\n";
print "Note: Memory measurements are peak RSS (Resident Set Size).\n";
print "JVM startup overhead is included in these measurements.\n";
print "For long-running processes, the overhead becomes less significant.\n";
