#!/usr/bin/env perl
use strict;
use warnings;

use File::Spec;
use FindBin;
use Getopt::Long qw(GetOptions);
use JSON::PP;

my $root = File::Spec->rel2abs(File::Spec->catdir($FindBin::Bin, '..', '..'));
my $perl_root;
my $format = 'tsv';
my $output;
my $capability_map;
my $help;
GetOptions(
    'perl-root=s' => \$perl_root,
    'format=s' => \$format,
    'output=s' => \$output,
    'check-capability-map=s' => \$capability_map,
    'help' => \$help,
) or usage(2);
usage(0) if $help;
usage(2) if @ARGV || $format !~ /\A(?:tsv|json)\z/;

$perl_root //= $ENV{PERLONJAVA_PERL_ROOT};
$perl_root //= File::Spec->catdir($root, 'perl5');

my @pods = qw(
    perlreref.pod
    perlrecharclass.pod
    perlrequick.pod
    perlrepository.pod
    perlre.pod
    perlretut.pod
    perlrebackslash.pod
);
my @records;
for my $pod (@pods) {
    my $path = File::Spec->catfile($perl_root, 'pod', $pod);
    open my $fh, '<:raw', $path or die "Cannot read $path: $!\n";
    my (@headings, %constructs);
    while (my $line = <$fh>) {
        push @headings, $1 if $line =~ /^=head\d\s+(.+)/;
        $constructs{$1}++ while $line =~ /(\(\?[^\s)]+|\(\*[^\s)]+|\\[pPNKkQERX])/g;
    }
    close $fh or die "Cannot close $path: $!\n";

    push @records, { type => 'FILE', pod => $pod, value => $pod };
    push @records, map { { type => 'HEADING', pod => $pod, value => $_ } }
        @headings;
    push @records, map { { type => 'CONSTRUCT', pod => $pod, value => $_ } }
        sort keys %constructs;
}

my %summary;
$summary{lc($_->{type}) . '_rows'}++ for @records;
$summary{total_rows} = scalar @records;

check_capability_map($capability_map, \@pods, \@records, \%summary)
    if defined $capability_map;

my $contents;
if ($format eq 'json') {
    my $inventory = {
        schema_version => 1,
        policy => 'current selected perl5 checkout; no pinned revision',
        pod_files => \@pods,
        summary => \%summary,
        records => \@records,
    };
    $contents = JSON::PP->new->canonical->pretty->encode($inventory);
} else {
    $contents = join '', map { "$_->{type}\t$_->{value}\n" } @records;
}

if (defined $output) {
    open my $fh, '>:raw', $output or die "Cannot write $output: $!\n";
    print {$fh} $contents;
    close $fh or die "Cannot close $output: $!\n";
} else {
    print $contents;
}

sub check_capability_map {
    my ($path, $expected_pods, $records, $summary) = @_;
    my $map = JSON::PP->new->decode(read_file($path));
    die "Capability map schema_version must be 1\n"
        unless ($map->{schema_version} // 0) == 1;
    die "Capability map must target current Perl without a pinned revision\n"
        unless ($map->{policy} // '') =~ /current/i
            && ($map->{policy} // '') =~ /no pinned revision/i;
    die "Capability map POD list differs from the extractor POD list\n"
        unless JSON::PP->new->canonical->encode($map->{pod_files} // []) eq
            JSON::PP->new->canonical->encode($expected_pods);

    my %excluded = map { $_ => 1 } @{ $map->{excluded_headings} // [] };
    my $excluded_rows = grep {
        $_->{type} eq 'HEADING' && $excluded{$_->{value}}
    } @$records;
    my $mapped_rows = ($summary->{construct_rows} // 0)
        + ($summary->{heading_rows} // 0) - $excluded_rows;
    my %actual = (
        file_rows => $summary->{file_rows} // 0,
        heading_rows => $summary->{heading_rows} // 0,
        construct_rows => $summary->{construct_rows} // 0,
        total_rows => $summary->{total_rows} // 0,
        excluded_heading_rows => $excluded_rows,
        mapped_capability_rows => $mapped_rows,
    );
    my $contract = $map->{inventory_contract} // {};
    for my $field (sort keys %actual) {
        die "Capability map inventory mismatch for $field: expected "
            . ($contract->{$field} // '<missing>') . ", actual $actual{$field}\n"
            unless defined $contract->{$field}
                && $contract->{$field} == $actual{$field};
    }

    my %ids;
    my $families = $map->{families};
    die "Capability map families must be a non-empty array\n"
        unless ref($families) eq 'ARRAY' && @$families;
    for my $family (@$families) {
        my $id = $family->{id} // '';
        die "Capability family has an invalid or duplicate id '$id'\n"
            unless $id =~ /\A[a-z0-9]+(?:-[a-z0-9]+)*\z/ && !$ids{$id}++;
        die "Capability family $id has no POD topics\n"
            unless ref($family->{pod_topics}) eq 'ARRAY'
                && @{ $family->{pod_topics} };
        die "Capability family $id has no source evidence\n"
            unless ref($family->{source_evidence}) eq 'ARRAY'
                && @{ $family->{source_evidence} };
        validate_project_paths($id, 'source evidence',
            $family->{source_evidence});
        validate_project_paths($id, 'focused test',
            $family->{focused_tests} // []);
        for my $imported (@{ $family->{imported_tests} // [] }) {
            die "Capability family $id has unsafe imported test '$imported'\n"
                unless $imported =~ m{\Are/[A-Za-z0-9_.-]+\.t\z};
        }
    }
    print STDERR "Capability map check passed: $actual{total_rows} rows; "
        . "$actual{mapped_capability_rows} mapped, "
        . "$actual{excluded_heading_rows} excluded, "
        . scalar(@$families) . " evidence families\n";
}

sub validate_project_paths {
    my ($id, $label, $paths) = @_;
    die "Capability family $id $label list must be an array\n"
        unless ref($paths) eq 'ARRAY';
    for my $relative (@$paths) {
        die "Capability family $id has unsafe $label path '$relative'\n"
            if File::Spec->file_name_is_absolute($relative)
                || grep { $_ eq '..' } File::Spec->splitdir($relative);
        my $path = File::Spec->catfile($root, File::Spec->splitdir($relative));
        die "Capability family $id references missing $label '$relative'\n"
            unless -f $path;
    }
}

sub read_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!\n";
    my $contents = do { local $/; <$fh> };
    close $fh or die "Cannot close $path: $!\n";
    return $contents;
}

sub usage {
    my ($status) = @_;
    print <<'USAGE';
Usage: extract_regex_pod_inventory.pl [OPTIONS]

Extract the seven current Perl regex POD files deterministically. The selected
checkout is always current input; no Perl revision or source hash is pinned.

Options:
  --perl-root DIR             selected/current Perl source checkout
  --format tsv|json           output format (default: tsv)
  --output FILE               write output to FILE instead of stdout
  --check-capability-map FILE validate inventory counts and evidence paths
  --help                      show this help
USAGE
    exit $status;
}
