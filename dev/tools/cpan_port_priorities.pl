#!/usr/bin/env perl

use strict;
use warnings;

use Getopt::Long qw(GetOptions);
use HTTP::Tiny;
use JSON::PP qw(decode_json encode_json);
use File::Basename qw(dirname);
use File::Spec;

my $root = File::Spec->rel2abs(File::Spec->catdir(dirname(__FILE__), '..', '..'));
my $report_dir = File::Spec->catdir($root, 'dev', 'cpan-reports');
my $pass_file = File::Spec->catfile($report_dir, 'cpan-compatibility-pass.dat');
my $fail_file = File::Spec->catfile($report_dir, 'cpan-compatibility-fail.dat');
my $skip_file = File::Spec->catfile($report_dir, 'cpan-compatibility-skip.dat');
my $cache_file = File::Spec->catfile(File::Spec->tmpdir, 'perlonjava-cpan-port-priorities-bulk.json');
my $xs_file = File::Spec->catfile($root, 'docs', 'reference', 'xs-compatibility.md');
my $bundled_file = File::Spec->catfile($root, 'docs', 'reference', 'bundled-modules.md');
my $top = 100;
my $include_skip = 0;
my $all_nonpassing = 0;
my $native_only = 0;
my $include_implemented = 0;
my $targeted = 0;
my $bulk_pages = 2;
my $sort_by = 'dependants';
my $refresh = 0;
my $help = 0;
my @only_modules;

GetOptions(
    'pass-file=s'     => \$pass_file,
    'fail-file=s'     => \$fail_file,
    'skip-file=s'     => \$skip_file,
    'cache=s'         => \$cache_file,
    'top=i'           => \$top,
    'include-skip'    => \$include_skip,
    'all-nonpassing'  => \$all_nonpassing,
    'native-only'     => \$native_only,
    'include-implemented' => \$include_implemented,
    'targeted'        => \$targeted,
    'bulk-pages=i'    => \$bulk_pages,
    'sort=s'          => \$sort_by,
    'module=s@'       => \@only_modules,
    'refresh'         => \$refresh,
    'help'            => \$help,
) or usage(2);

usage(0) if $help;
die "--top must be positive\n" unless $top > 0;
die "--sort must be dependants, recent, or score\n"
    unless $sort_by eq 'dependants' || $sort_by eq 'recent' || $sort_by eq 'score';

my %passed = read_status_file($pass_file);
my %implemented = read_implemented_inventory($xs_file, $bundled_file);
my %candidates;

add_candidates($fail_file, 'FAIL');
add_candidates($skip_file, 'SKIP') if $include_skip;

my $cache = read_cache($cache_file);
my $http = HTTP::Tiny->new(timeout => 30);
my @rows;

my $bulk_index;
if (!$targeted) {
    $bulk_index = bulk_reverse_dependencies(\%candidates, \%passed, $cache, $http);
}

for my $module (sort keys %candidates) {
    my $candidate = $candidates{$module};
    next unless eligible_candidate($module, $candidate, \%passed, \%implemented);

    my $reverse = $targeted
        ? reverse_dependencies($module, $cache, $http)
        : ($bulk_index->{$module} || []);
    next unless $reverse;

    my %seen;
    my $total = 0;
    my $recent = 0;
    my $examples = '';
    for my $dependent (@$reverse) {
        my $distribution = $dependent->{distribution} || $dependent->{name} || '';
        next unless length $distribution;
        next if $seen{$distribution}++;
        $total++;
        my $date = $dependent->{date} || '';
        $recent++ if $date ge recent_cutoff();
        if (length($examples) < 180) {
            $examples .= ', ' if length $examples;
            $examples .= $distribution;
        }
    }

    my $score = $total + (2 * $recent);
    push @rows, {
        module       => $module,
        status       => $candidate->{status},
        summary      => $candidate->{summary},
        total        => $total,
        recent       => $recent,
        score        => $score,
        signal       => portability_signal($candidate->{summary}),
        examples     => $examples,
    };
}

write_cache($cache_file, $cache);

if ($sort_by eq 'recent') {
    @rows = sort {
           $b->{recent} <=> $a->{recent}
        || $b->{total} <=> $a->{total}
        || $a->{module} cmp $b->{module}
    } @rows;
} elsif ($sort_by eq 'score') {
    @rows = sort {
           $b->{score} <=> $a->{score}
        || $b->{total} <=> $a->{total}
        || $a->{module} cmp $b->{module}
    } @rows;
} else {
    @rows = sort {
           $b->{total} <=> $a->{total}
        || $b->{recent} <=> $a->{recent}
        || $a->{module} cmp $b->{module}
    } @rows;
}
splice @rows, $top if @rows > $top;

print join("\t", qw(rank module status porting_signal reverse_dependents recent_dependents score summary examples)), "\n";
for my $i (0 .. $#rows) {
    my $row = $rows[$i];
    print join("\t",
        $i + 1,
        map { clean_field($_) } @{$row}{qw(module status signal total recent score summary examples)}
    ), "\n";
}

sub add_candidates {
    my ($file, $status) = @_;
    open my $fh, '<', $file or die "Cannot read $file: $!\n";
    while (my $line = <$fh>) {
        chomp $line;
        $line =~ s/\r$//;
        next unless length $line;
        my @fields = split /\t/, $line, -1;
        my $module = $fields[0] // '';
        next unless length $module && $module ne 'Module';
        $candidates{$module} ||= {
            status  => $status,
            summary => $fields[4] // '',
        };
    }
    close $fh;
}

sub eligible_candidate {
    my ($module, $candidate, $passed, $implemented) = @_;
    return 0 if @only_modules && !grep { $_ eq $module } @only_modules;
    return 0 if $passed->{$module};
    return 0 if !$include_implemented && $implemented->{$module};
    return 0 if !$all_nonpassing && $candidate->{summary} =~ /unknown test outcome|timeout/i;
    return 0 if $native_only && $candidate->{summary} !~ /xs|native|java xs|loadable object|ffm/i;
    return 1;
}

sub read_status_file {
    my ($file) = @_;
    open my $fh, '<', $file or die "Cannot read $file: $!\n";
    my %status;
    while (my $line = <$fh>) {
        my ($module) = split /\t/, $line, 2;
        chomp $module if defined $module;
        $status{$module} = 1 if defined($module) && length($module);
    }
    close $fh;
    return %status;
}

sub reverse_dependencies {
    my ($module, $cache, $http) = @_;
    return $cache->{$module}{data} if !$refresh && exists $cache->{$module};

    my @all;
    for my $page (1 .. 1000) {
        my $url = 'https://fastapi.metacpan.org/v1/reverse_dependencies/module/'
            . path_escape($module) . "?page=$page";
        my $response = $http->get($url);
        if (!$response->{success}) {
            warn "MetaCPAN request failed for $module page $page: "
                . "$response->{status} $response->{reason}\n";
            return;
        }

        my $decoded = eval { decode_json($response->{content}) };
        if (!$decoded || ref($decoded->{data}) ne 'ARRAY') {
            warn "MetaCPAN returned no dependency data for $module page $page\n";
            return;
        }
        push @all, @{$decoded->{data}};
        last if @{$decoded->{data}} < 50;
    }
    $cache->{$module} = { fetched_at => scalar gmtime, data => \@all };
    return \@all;
}

sub bulk_reverse_dependencies {
    my ($candidates, $passed, $cache, $http) = @_;
    return $cache->{bulk}{data} if !$refresh && ref($cache->{bulk}{data}) eq 'HASH';

    my %wanted = map {
        $_ => 1
    } grep {
        eligible_candidate($_, $candidates->{$_}, $passed, \%implemented)
    } keys %$candidates;
    my %reverse = map { $_ => [] } keys %wanted;
    return \%reverse unless %wanted;

    my $page_size = 5000;
    my $query = {
        size  => $page_size,
        _source => [qw(distribution dependency date)],
        query => {
            bool => {
                must => [
                    { term  => { maturity => 'released' } },
                    { term  => { status   => 'latest' } },
                ],
            },
        },
    };
    my $total_seen = 0;
    for my $page (0 .. $bulk_pages - 1) {
        my $response = $http->post(
            'https://fastapi.metacpan.org/v1/release/_search?from=' . ($page * $page_size),
            json_request($query),
        );
        my $decoded = decode_response($response, 'bulk MetaCPAN search page ' . ($page + 1));
        last unless $decoded;
        my $hits = $decoded->{hits}{hits};
        last unless ref($hits) eq 'ARRAY' && @$hits;
        $total_seen += scalar @$hits;
        for my $hit (@$hits) {
            my $source = $hit->{_source} || {};
            my $distribution = $source->{distribution} || '';
            next unless length $distribution;
            my %seen;
            for my $dependency (@{$source->{dependency} || []}) {
                next unless ref($dependency) eq 'HASH';
                next unless ($dependency->{phase} || 'runtime') eq 'runtime';
                next unless ($dependency->{relationship} || 'requires') eq 'requires';
                my $module = $dependency->{module} || '';
                next unless $wanted{$module};
                next if $seen{$module}++;
                push @{$reverse{$module}}, {
                    distribution => $distribution,
                    date         => $source->{date} || '',
                };
            }
        }
        last if @$hits < $page_size;
    }
    warn "MetaCPAN bulk result may be truncated at $total_seen releases; "
        . "use --bulk-pages to request more pages (the API currently caps each page at 5,000)\n"
        if $total_seen == $page_size * $bulk_pages;
    $cache->{bulk} = { fetched_at => scalar gmtime, data => \%reverse };
    return \%reverse;
}

sub json_request {
    my ($payload) = @_;
    return {
        headers => { 'content-type' => 'application/json' },
        content => encode_json($payload),
    };
}

sub decode_response {
    my ($response, $operation) = @_;
    if (!$response->{success}) {
        warn "$operation failed: $response->{status} $response->{reason}\n";
        return;
    }
    my $decoded = eval { decode_json($response->{content}) };
    if (!$decoded) {
        warn "$operation returned invalid JSON: $@\n";
        return;
    }
    return $decoded;
}

sub read_implemented_inventory {
    my (@files) = @_;
    my %implemented;
    for my $file (@files) {
        open my $fh, '<', $file or die "Cannot read implementation inventory $file: $!\n";
        while (my $line = <$fh>) {
            next unless $line =~ /^\|\s*(?:`([^`]+)`|([^|]+))\s*\|/;
            my $field = defined($1) ? $1 : $2;
            for my $module (split /\s*\/\s*/, $field) {
                $module =~ s/\s*\(.*\z//;
                $module =~ s/\s+$//;
                $implemented{$module} = 1 if $module =~ /^[A-Za-z_][A-Za-z0-9_:]*$/;
            }
        }
        close $fh;
    }
    return %implemented;
}

sub read_cache {
    my ($file) = @_;
    return {} unless -f $file;
    open my $fh, '<', $file or die "Cannot read cache $file: $!\n";
    local $/;
    my $text = <$fh>;
    close $fh;
    my $cache = eval { decode_json($text) };
    return ref($cache) eq 'HASH' ? $cache : {};
}

sub write_cache {
    my ($file, $cache) = @_;
    open my $fh, '>', $file or die "Cannot write cache $file: $!\n";
    print {$fh} encode_json($cache);
    close $fh;
}

sub path_escape {
    my ($value) = @_;
    $value =~ s/([^A-Za-z0-9_.~-])/sprintf('%%%02X', ord($1))/eg;
    return $value;
}

sub recent_cutoff {
    my @now = gmtime;
    my $year = $now[5] + 1900 - 3;
    return sprintf('%04d-%02d-%02d', $year, $now[4] + 1, $now[3]);
}

sub portability_signal {
    my ($summary) = @_;
    return 'native-or-xs' if $summary =~ /xs|native|java xs|loadable object|ffm/i;
    return 'timeout-or-environment' if $summary =~ /unknown test outcome|timeout/i;
    return 'runtime-or-test-failure';
}

sub clean_field {
    my ($value) = @_;
    $value //= '';
    $value =~ s/[\t\r\n]+/ /g;
    return $value;
}

sub usage {
    my ($exit) = @_;
    print <<'USAGE';
Usage: cpan_port_priorities.pl [options]

Rank CPAN modules that do not appear in the PerlOnJava pass report by the
number and recency of their MetaCPAN reverse dependents.

Options:
  --top N             Emit at most N rows (default: 100)
  --include-skip      Include SKIP records as candidates
  --all-nonpassing    Include timeout and unknown-outcome records
  --native-only       Keep only records whose report text signals XS/native use
  --include-implemented
                      Include modules already listed in bundled/XS inventories
  --targeted          Use one reverse-dependency API request per module
  --bulk-pages N       Number of 5,000-result bulk pages (default: 2)
  --sort MODE          Sort by dependants (default), recent, or score
  --module NAME       Restrict the query to one or more named modules
  --refresh           Ignore cached MetaCPAN responses
  --cache FILE        Cache path (default: system temporary directory)
  --pass-file FILE    Override the pass report
  --fail-file FILE    Override the fail report
  --skip-file FILE    Override the skip report
  --help              Show this help

The default intentionally excludes SKIP, timeout, and unknown-outcome rows:
those need classification before they should drive a porting decision.
USAGE
    exit $exit;
}
