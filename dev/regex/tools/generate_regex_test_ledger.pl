#!/usr/bin/env perl
use strict;
use warnings;

use Cwd qw(abs_path);
use File::Find qw(find);
use File::Spec;
use Getopt::Long qw(GetOptions);
use JSON::PP;

my $tests_root = 'perl5_t/t';
my $unit_root = 'src/test/resources/unit';
my $scope = 'regex';
my @references;
my $runner_list;
my $output;
my $help;
GetOptions(
    'tests-root=s' => \$tests_root,
    'unit-root=s' => \$unit_root,
    'scope=s' => \$scope,
    'reference=s@' => \@references,
    'runner-list=s' => \$runner_list,
    'output=s' => \$output,
    'help' => \$help,
) or usage(2);
usage(0) if $help;
usage(2) if @ARGV || $scope !~ /\A(?:regex|complete)\z/;

@references = (
    'docs/reference/feature-matrix.md',
    'dev/design/regex-implementation.md',
    'dev/tools/compare_test_results.pl',
) unless @references;

my @core = files_below(File::Spec->catdir($tests_root, 're'));
my @auxiliary = grep { regex_bearing($_) }
    map { files_below(File::Spec->catdir($tests_root, $_)) } qw(op uni);

my (%documented, @unresolved);
for my $reference (@references) {
    next unless -f $reference;
    my $contents = read_file($reference);
    while ($contents =~ /`([^`\n]*?\.t)`/g) {
        my $spelling = $1;
        my @resolved = resolve_reference($spelling, $tests_root, $unit_root);
        if (@resolved) {
            $documented{$_} = 1 for @resolved;
        } else {
            push @unresolved, { source => $reference, spelling => $spelling };
        }
    }
}

my @complete = $scope eq 'complete' ? files_below($tests_root) : ();
my %runner = map { $_ => 1 } ($scope eq 'complete' ? @complete : (@core, @auxiliary),
    grep { index($_, "$tests_root/") == 0 } keys %documented);
my @runner = sort keys %runner;
my @unit_gates = sort grep { index($_, "$unit_root/") == 0 } keys %documented;

my @thread_pairs;
my @thread_only;
for my $thread (grep { /_thr\.t\z/ } @core) {
    (my $direct = $thread) =~ s/_thr\.t\z/.t/;
    if (-f $direct) {
        push @thread_pairs, { direct => $direct, thread => $thread };
    } else {
        push @thread_only, $thread;
    }
}

my $ledger = {
    schema_version => 1,
    policy => 'current latest upstream perl5 checkout; no pinned revision',
    scope => $scope,
    summary => {
        core_re_files => scalar @core,
        auxiliary_regex_files => scalar @auxiliary,
        runner_files => scalar @runner,
        documented_unit_gates => scalar @unit_gates,
        direct_thread_pairs => scalar @thread_pairs,
        thread_only_tests => scalar @thread_only,
        unresolved_references => scalar @unresolved,
    },
    core_re_files => \@core,
    auxiliary_regex_files => \@auxiliary,
    runner_files => \@runner,
    documented_unit_gates => \@unit_gates,
    direct_thread_pairs => \@thread_pairs,
    thread_only_tests => \@thread_only,
    unresolved_references => \@unresolved,
};

write_lines($runner_list, @runner) if defined $runner_list;
my $json = JSON::PP->new->canonical->pretty->encode($ledger);
if (defined $output) {
    write_file($output, $json);
} else {
    print $json;
}

sub usage {
    my ($status) = @_;
    print <<'USAGE';
Usage: generate_regex_test_ledger.pl [OPTIONS]

Mechanically derive the current Regex implementation regex test ledger from the complete
perl5_t/t/re corpus, regex-bearing op/ and uni/ files, and documented test
references. The checkout is always treated as current upstream input; no Perl
revision or historical checksum is encoded.

Options:
  --tests-root DIR    imported Perl test root (default: perl5_t/t)
  --unit-root DIR     repository unit-test root
  --scope MODE        regex (default) or complete imported test discovery
  --reference FILE    documentation/tool reference source (repeatable)
  --runner-list FILE  write one runnable imported test path per line
  --output FILE       write canonical JSON instead of stdout
  --help              show this help
USAGE
    exit $status;
}

sub files_below {
    my ($root) = @_;
    return () unless -d $root;
    my @files;
    find({
        no_chdir => 1,
        wanted => sub {
            return unless -f $_ && /\.t\z/;
            my $path = $File::Find::name;
            $path =~ s{\\}{/}g;
            push @files, $path;
        },
    }, $root);
    return sort @files;
}

sub regex_bearing {
    my ($path) = @_;
    my $source = read_file($path);
    return 1 if $source =~ /(?:=~|!~)/;
    return 1 if $source =~ /\b(?:qr|split|pos)\b/;
    return 1 if $source =~ /\buse\s+re\b/;
    return 1 if $source =~ /\\[pPNX]\{/;
    return 0;
}

sub resolve_reference {
    my ($spelling, $tests, $units) = @_;
    $spelling =~ s{\\}{/}g;
    my @candidates;
    if ($spelling =~ m{\Aperl5_t/t/}) {
        (my $relative = $spelling) =~ s{\Aperl5_t/t/}{};
        my $candidate = safe_nested_candidate($tests, $relative);
        push @candidates, $candidate if defined $candidate;
    } elsif ($spelling =~ m{/}) {
        # Documented imported gates also live outside the three directories
        # scanned mechanically (for example japh/abigail.t).  Resolve safe
        # relative test paths beneath the current tests root. Canonical
        # containment also rejects a nested symlink that escapes that root.
        my $candidate = safe_nested_candidate($tests, $spelling);
        push @candidates, $candidate if defined $candidate;
    } else {
        for my $root ($tests, $units) {
            push @candidates, grep {
                /\Q$spelling\E\z/ && candidate_within_root($_, $root)
            } files_below($root);
        }
    }
    my %seen;
    return sort grep { -f $_ && !$seen{$_}++ } @candidates;
}

sub safe_nested_candidate {
    my ($root, $relative) = @_;
    return if File::Spec->file_name_is_absolute($relative);
    my @parts = split m{/}, $relative, -1;
    return if !@parts || grep {
        $_ eq '' || $_ eq '.' || $_ eq '..' || $_ !~ /\A[A-Za-z0-9_.-]+\z/
    } @parts;

    my $candidate = File::Spec->catfile($root, @parts);
    return unless -f $candidate;
    return $candidate if candidate_within_root($candidate, $root);
    return;
}

sub candidate_within_root {
    my ($candidate, $root) = @_;
    my $canonical_root = abs_path($root);
    my $canonical_candidate = abs_path($candidate);
    return 0 unless defined $canonical_root && defined $canonical_candidate;
    my $contained = File::Spec->abs2rel($canonical_candidate, $canonical_root);
    return 0 if File::Spec->file_name_is_absolute($contained);
    return 0 if grep { $_ eq '..' } File::Spec->splitdir($contained);
    return 1;
}

sub read_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!\n";
    my $contents = do { local $/; <$fh> };
    close $fh or die "Cannot close $path: $!\n";
    return $contents;
}

sub write_lines {
    my ($path, @lines) = @_;
    write_file($path, join('', map { "$_\n" } @lines));
}

sub write_file {
    my ($path, $contents) = @_;
    open my $fh, '>:raw', $path or die "Cannot write $path: $!\n";
    print {$fh} $contents;
    close $fh or die "Cannot close $path: $!\n";
}
