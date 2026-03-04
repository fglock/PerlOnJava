#!/usr/bin/env perl
use strict;
use warnings;
use utf8;
binmode STDOUT, ':utf8';
binmode STDERR, ':utf8';
use File::Spec;
use File::Basename;
use Time::HiRes qw(time);
use Getopt::Long;
use POSIX qw(WNOHANG);

# Image::ExifTool Test Runner for PerlOnJava
# Runs ExifTool's test suite against PerlOnJava and reports results.
#
# Usage:
#   perl dev/tools/run_exiftool_tests.pl [OPTIONS] [test_file ...]
#
# Examples:
#   perl dev/tools/run_exiftool_tests.pl                    # run all tests
#   perl dev/tools/run_exiftool_tests.pl t/Writer.t t/XMP.t # run specific tests
#   perl dev/tools/run_exiftool_tests.pl --jobs 4 --timeout 120
#   perl dev/tools/run_exiftool_tests.pl --compare          # compare with system perl

my $jperl_path  = './jperl';
my $exiftool_dir = 'Image-ExifTool-13.44';
my $timeout     = 120;
my $jobs        = 8;
my $output_file;
my $compare;
my $help;

GetOptions(
    'jperl=s'      => \$jperl_path,
    'exiftool=s'   => \$exiftool_dir,
    'timeout=f'    => \$timeout,
    'jobs|j=i'     => \$jobs,
    'output=s'     => \$output_file,
    'compare'      => \$compare,
    'help'         => \$help,
) or die "Error in command line arguments\n";

if ($help) {
    print_usage();
    exit 0;
}

die "Error: ExifTool directory '$exiftool_dir' not found.\n" unless -d $exiftool_dir;
die "Error: jperl not found at '$jperl_path'\n" unless -x $jperl_path;

my $abs_jperl   = File::Spec->rel2abs($jperl_path);
my $abs_exiftool = File::Spec->rel2abs($exiftool_dir);

my @test_files;
if (@ARGV) {
    for my $arg (@ARGV) {
        my $path = $arg;
        $path = "$exiftool_dir/$arg" if !-f $path && -f "$exiftool_dir/$arg";
        die "Error: test file '$arg' not found\n" unless -f $path;
        push @test_files, File::Spec->rel2abs($path);
    }
} else {
    @test_files = sort glob("$abs_exiftool/t/*.t");
}

die "Error: No .t files found in $exiftool_dir/t/\n" unless @test_files;

my $total = scalar @test_files;
my $timeout_cmd = find_timeout_cmd();

printf "ExifTool test suite: %d test files\n", $total;
printf "Runner: %s  Timeout: %ds  Jobs: %d\n", $abs_jperl, $timeout, $jobs;
print "-" x 60, "\n";

my (%results, %children);
my @queue = @test_files;
my $completed = 0;

local $SIG{CHLD} = 'DEFAULT';

while (@queue && keys(%children) < $jobs) {
    launch_job(\@queue, \%children);
}

while (%children || @queue) {
    for my $pid (keys %children) {
        my $res = waitpid($pid, WNOHANG);
        if ($res > 0) {
            harvest($pid);
            $completed++;
            if (@queue && keys(%children) < $jobs) {
                launch_job(\@queue, \%children);
            }
        } elsif ($res < 0) {
            delete $children{$pid};
        }
    }
    select(undef, undef, undef, 0.05) if %children;
}

print "-" x 60, "\n";
print_summary();

if ($compare) {
    run_perl_comparison();
}

if ($output_file) {
    save_json($output_file);
}

exit( (grep { $_->{status} ne 'pass' } values %results) ? 1 : 0 );

# ---- subroutines ----

sub find_timeout_cmd {
    return "timeout ${timeout}s "  if system('which timeout  >/dev/null 2>&1') == 0;
    return "gtimeout ${timeout}s " if system('which gtimeout >/dev/null 2>&1') == 0;
    return '';
}

sub launch_job {
    my ($queue, $children) = @_;
    my $test_file = shift @$queue or return;
    my $index = $total - @$queue;

    my $pid = fork();
    die "Cannot fork: $!" unless defined $pid;
    if ($pid == 0) {
        my $result = run_test($test_file);
        my $tmp = "/tmp/exif_test_$$" . "_" . time() . ".dat";
        if (open my $fh, '>', $tmp) {
            # Simple serialization: key=value lines
            print $fh "file=$test_file\n";
            print $fh "index=$index\n";
            for my $k (qw(status pass fail planned exit_code)) {
                print $fh "$k=$result->{$k}\n";
            }
            if ($result->{errors} && @{$result->{errors}}) {
                print $fh "error=$result->{errors}[0]\n";
            }
            close $fh;
        }
        exit 0;
    }
    $children->{$pid} = { file => $test_file, index => $index, start => time() };
}

sub harvest {
    my ($pid) = @_;
    my $info = delete $children{$pid};
    my $duration = time() - $info->{start};
    my $name = basename($info->{file}, '.t');

    # Find result file from child
    my $result;
    for my $f (glob("/tmp/exif_test_*.dat")) {
        if (open my $fh, '<', $f) {
            my %data;
            while (<$fh>) {
                chomp;
                my ($k, $v) = split /=/, $_, 2;
                $data{$k} = $v if defined $k;
            }
            close $fh;
            if ($data{file} && $data{file} eq $info->{file}) {
                $result = \%data;
                unlink $f;
                last;
            }
        }
    }

    $result //= { status => 'error', pass => 0, fail => 0, planned => 0, error => 'no result' };
    $result->{duration} = sprintf("%.1f", $duration);
    $result->{name} = $name;
    $results{$name} = $result;

    my %sym = (pass => "\x{2713}", fail => "\x{2717}", error => '!', timeout => 'T');
    my $ch = $sym{$result->{status}} // '?';

    printf "[%3d/%d] %-30s %s  %d/%d ok  (%.1fs)\n",
        $info->{index}, $total, $name, $ch,
        $result->{pass}, $result->{planned} || $result->{pass} + $result->{fail},
        $duration;
}

sub run_test {
    my ($test_file) = @_;
    my $old_dir = File::Spec->rel2abs('.');
    chdir $abs_exiftool or return { status => 'error', pass => 0, fail => 0, planned => 0, errors => ["chdir failed"] };

    my $rel = File::Spec->abs2rel($test_file, $abs_exiftool);
    my $cmd = "${timeout_cmd}$abs_jperl -Ilib $rel 2>&1";
    my $output = `$cmd`;
    my $exit_code = $? >> 8;

    chdir $old_dir;

    return { status => 'timeout', pass => 0, fail => 0, planned => 0, exit_code => 124, errors => ['timed out'] }
        if $exit_code == 124;

    parse_tap($output, $exit_code);
}

sub parse_tap {
    my ($output, $exit_code) = @_;
    $output //= '';
    my ($pass, $fail, $planned) = (0, 0, 0);
    my @errors;

    for my $line (split /\n/, $output) {
        $planned = $1 if $line =~ /^1\.\.(\d+)/;
        $pass++        if $line =~ /^ok\s+\d+/;
        if ($line =~ /^not ok\s+\d+/) {
            $fail++;
            push @errors, $line;
        }
    }

    my $total = $pass + $fail;
    my $status;
    if ($exit_code == 124) {
        $status = 'timeout';
    } elsif ($total == 0 && $exit_code != 0) {
        $status = 'error';
        push @errors, "exit code $exit_code with no TAP output";
    } elsif ($fail == 0 && $pass > 0) {
        $status = 'pass';
    } elsif ($fail > 0) {
        $status = 'fail';
    } elsif ($planned > 0 && $total < $planned) {
        $status = 'error';
        push @errors, "planned $planned but ran $total";
    } else {
        $status = $exit_code == 0 ? 'pass' : 'error';
    }

    return {
        status    => $status,
        pass      => $pass,
        fail      => $fail,
        planned   => $planned,
        exit_code => $exit_code,
        errors    => \@errors,
    };
}

sub print_summary {
    my (%s, $total_pass, $total_fail, $total_planned);
    $total_pass = $total_fail = $total_planned = 0;
    for my $r (values %results) {
        $s{$r->{status}}++;
        $total_pass    += $r->{pass}    // 0;
        $total_fail    += $r->{fail}    // 0;
        $total_planned += $r->{planned} // 0;
    }

    print "\nEXIFTOOL TEST SUMMARY:\n";
    printf "  Test files:  %d\n", scalar keys %results;
    printf "  Passed:      %d\n", $s{pass}    // 0;
    printf "  Failed:      %d\n", $s{fail}    // 0;
    printf "  Errors:      %d\n", $s{error}   // 0;
    printf "  Timeouts:    %d\n", $s{timeout}  // 0;
    print  "\n";
    printf "  Total tests: %d\n", $total_pass + $total_fail;
    printf "  OK:          %d\n", $total_pass;
    printf "  Not OK:      %d\n", $total_fail;

    if ($total_pass + $total_fail > 0) {
        printf "  Pass rate:   %.1f%%\n", $total_pass * 100 / ($total_pass + $total_fail);
    }

    my @failures = sort grep { $results{$_}{status} ne 'pass' } keys %results;
    if (@failures) {
        print "\nFAILED/ERROR TESTS:\n";
        for my $name (@failures) {
            my $r = $results{$name};
            printf "  %-30s %s", $name, $r->{status};
            printf "  (%d/%d ok)", $r->{pass}, $r->{planned} || ($r->{pass} + $r->{fail})
                if $r->{pass} || $r->{fail};
            if ($r->{error}) {
                my $err = $r->{error};
                $err = substr($err, 0, 60) . "..." if length($err) > 60;
                print "  $err";
            }
            print "\n";
        }
    }
}

sub run_perl_comparison {
    print "\nCOMPARISON WITH SYSTEM PERL:\n";
    my $perl = `which perl 2>/dev/null`;
    chomp $perl;
    unless ($perl) {
        print "  (system perl not found, skipping comparison)\n";
        return;
    }
    printf "  System perl: %s\n\n", $perl;

    my @failures = sort grep { $results{$_}{status} ne 'pass' } keys %results;
    return unless @failures;

    my $old_dir = File::Spec->rel2abs('.');
    chdir $abs_exiftool;

    printf "  %-30s %-12s %-12s\n", "Test", "jperl", "perl";
    printf "  %-30s %-12s %-12s\n", "-" x 28, "-" x 10, "-" x 10;

    for my $name (@failures[0 .. ($#failures > 9 ? 9 : $#failures)]) {
        my $jr = $results{$name};
        my $t_file = "t/$name.t";
        next unless -f $t_file;
        my $pout = `$perl -Ilib $t_file 2>&1`;
        my $pr = parse_tap($pout, $? >> 8);
        printf "  %-30s %d/%d %-6s  %d/%d %-6s\n",
            $name,
            $jr->{pass}, $jr->{planned} || ($jr->{pass} + $jr->{fail}), $jr->{status},
            $pr->{pass}, $pr->{planned} || ($pr->{pass} + $pr->{fail}), $pr->{status};
    }

    chdir $old_dir;
}

sub save_json {
    my ($file) = @_;
    eval { require JSON::PP };
    if ($@) {
        warn "JSON::PP not available, skipping JSON output\n";
        return;
    }
    my $data = {
        timestamp   => scalar(localtime),
        jperl       => $abs_jperl,
        exiftool    => $abs_exiftool,
        total_files => $total,
        results     => \%results,
    };
    open my $fh, '>', $file or die "Cannot write $file: $!\n";
    print $fh JSON::PP->new->pretty->canonical->encode($data);
    close $fh;
    print "\nDetailed results saved to: $file\n";
}

sub print_usage {
    print <<"EOF";
Usage: $0 [OPTIONS] [test_file ...]

Run Image::ExifTool tests against PerlOnJava.

If no test files are specified, runs all t/*.t files in the ExifTool directory.

Options:
  --jperl PATH      Path to jperl executable (default: ./jperl)
  --exiftool DIR    Path to ExifTool directory (default: Image-ExifTool-13.44)
  --timeout SEC     Timeout per test in seconds (default: 120)
  --jobs|-j NUM     Number of parallel jobs (default: 8)
  --output FILE     Save detailed results to JSON file
  --compare         After running, compare failures against system perl
  --help            Show this help message

Examples:
  $0                                           # run all ExifTool tests
  $0 t/Writer.t t/XMP.t                       # run specific tests
  $0 --compare --output exiftool_results.json  # full run with comparison
  $0 --jobs 4 --timeout 180                    # slower but safer
EOF
}
