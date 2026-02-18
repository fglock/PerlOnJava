#!/usr/bin/env perl
use strict;
use warnings;
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

# Check for --interpreter flag
my $use_interpreter = 0;
if (@ARGV && $ARGV[0] eq '--interpreter') {
    $use_interpreter = 1;
    $jperl .= ' --interpreter';
}

# Get current RSS memory in KB (works on macOS and Linux)
sub get_current_memory {
    if ($^O eq 'darwin') {
        # macOS: use ps
        my $pid = $$;
        my $output = `ps -o rss= -p $pid`;
        chomp $output;
        $output =~ s/^\s+//;
        return $output; # Already in KB on macOS
    } elsif ($^O eq 'linux') {
        # Linux: read /proc/self/status
        open my $fh, '<', '/proc/self/status' or return undef;
        while (<$fh>) {
            if (/^VmRSS:\s+(\d+)\s+kB/) {
                close $fh;
                return $1;
            }
        }
        close $fh;
        return undef;
    } else {
        return undef;
    }
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

# Memory benchmark workloads - sized so Perl 5 uses at least 100MB per test
my @workloads = (
    {
        name => "Array creation (15M elements)",
        code => q{
            my $mem_before = get_current_memory();
            my @arr = (1..15_000_000);
            # Force memory measurement while array is still in scope
            my $mem_after = get_current_memory();
            my $delta = $mem_after - $mem_before;
            print "DELTA:$delta\n";
            # Use the array to prevent GC optimization
            my $sum = 0; $sum += $_ for @arr;
            print "RESULT:$sum\n";
        },
    },
    {
        name => "Hash creation (2M entries)",
        code => q{
            my $mem_before = get_current_memory();
            my %hash; $hash{$_} = $_ * 2 for (1..2_000_000);
            # Force memory measurement while hash is still in scope
            my $mem_after = get_current_memory();
            my $delta = $mem_after - $mem_before;
            print "DELTA:$delta\n";
            # Use the hash to prevent GC optimization
            my $sum = 0; $sum += $hash{$_} for keys %hash;
            print "RESULT:$sum\n";
        },
    },
    {
        name => "String buffer (100M chars)",
        code => q{
            my $mem_before = get_current_memory();
            my $str = "x" x 100000;
            my $result = "";
            for (1..1000) { $result .= $str; }
            # Force memory measurement while strings are still in scope
            my $mem_after = get_current_memory();
            my $delta = $mem_after - $mem_before;
            print "DELTA:$delta\n";
            # Use the result to prevent GC optimization
            print "RESULT:" . length($result) . "\n";
        },
    },
    {
        name => "Nested data structures (30K objects)",
        code => q{
            my $mem_before = get_current_memory();
            my @data;
            for my $i (1..30_000) {
                push @data, { id => $i, values => [1..100] };
            }
            # Force memory measurement while data is still in scope
            my $mem_after = get_current_memory();
            my $delta = $mem_after - $mem_before;
            print "DELTA:$delta\n";
            # Use the data to prevent GC optimization
            my $sum = 0;
            for my $item (@data) {
                $sum += scalar(@{$item->{values}});
            }
            print "RESULT:$sum\n";
        },
    },
);

my $mode_name = $use_interpreter ? "jperl --interpreter" : "jperl (compiler)";
print "# Memory Delta Benchmark: perl vs $mode_name\n\n";
print "Measuring memory delta (before/after data creation) to exclude startup overhead.\n\n";

sub run_benchmark {
    my ($interpreter, $code) = @_;
    
    # Create a script that includes the get_current_memory function
    my $full_code = q{
use strict;
use warnings;

sub get_current_memory {
    if ($^O eq 'darwin') {
        my $pid = $$;
        my $output = `ps -o rss= -p $pid`;
        chomp $output;
        $output =~ s/^\s+//;
        return $output;
    } elsif ($^O eq 'linux') {
        open my $fh, '<', '/proc/self/status' or return undef;
        while (<$fh>) {
            if (/^VmRSS:\s+(\d+)\s+kB/) {
                close $fh;
                return $1;
            }
        }
        close $fh;
        return undef;
    } else {
        return undef;
    }
}

} . $code;
    
    my $tmpfile = "/tmp/perlbench_delta_$$.pl";
    open my $fh, '>', $tmpfile or die "Cannot write to $tmpfile: $!";
    print $fh $full_code;
    close $fh;
    
    my $output = `$interpreter $tmpfile 2>&1`;
    unlink $tmpfile;
    
    # Parse output
    my ($delta, $result);
    if ($output =~ /DELTA:(\d+)/) {
        $delta = $1;
    }
    if ($output =~ /RESULT:(\d+)/) {
        $result = $1;
    }
    
    return ($delta, $result);
}

# Run benchmarks
my @results;

for my $workload (@workloads) {
    print "Running: $workload->{name}\n";
    
    # Run with perl
    my ($perl_delta, $perl_result) = run_benchmark('perl', $workload->{code});
    print "  perl:  " . format_memory($perl_delta) . " (result: $perl_result)\n";
    
    # Run with jperl
    my ($jperl_delta, $jperl_result) = run_benchmark($jperl, $workload->{code});
    print "  jperl: " . format_memory($jperl_delta) . " (result: $jperl_result)\n";
    
    my $ratio = "N/A";
    if (defined $perl_delta && defined $jperl_delta && $perl_delta > 0) {
        $ratio = sprintf("%.2fx", $jperl_delta / $perl_delta);
    }
    print "  ratio: $ratio\n\n";
    
    push @results, {
        name => $workload->{name},
        perl_delta => $perl_delta,
        jperl_delta => $jperl_delta,
        ratio => $ratio,
    };
}

# Print summary table
print "\n# Summary\n\n";
print "| Workload | Perl 5 Delta | PerlOnJava Delta | Ratio (jperl/perl) |\n";
print "|----------|--------------|------------------|--------------------|\n";

for my $result (@results) {
    printf "| %-40s | %12s | %16s | %18s |\n",
        $result->{name},
        format_memory($result->{perl_delta}),
        format_memory($result->{jperl_delta}),
        $result->{ratio};
}

print "\n";
print "Note: Delta measurements show memory increase during data creation.\n";
print "This excludes interpreter/JVM startup overhead.\n";
print "Measures actual memory used by data structures.\n";
