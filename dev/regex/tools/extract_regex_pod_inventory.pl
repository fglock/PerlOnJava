#!/usr/bin/env perl
use strict;
use warnings;

use File::Spec;
use FindBin;
use Getopt::Long qw(GetOptions);
use JSON::PP;
use Digest::SHA qw(sha256_hex);

my $root = File::Spec->rel2abs(File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
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
my %pod_sha256;
for my $pod (@pods) {
    my $path = File::Spec->catfile($perl_root, 'pod', $pod);
    open my $fh, '<:raw', $path or die "Cannot read $path: $!\n";
    my $contents = do { local $/; <$fh> };
    seek $fh, 0, 0 or die "Cannot rewind $path: $!\n";
    $pod_sha256{$pod} = sha256_hex($contents);
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
    push @records, { type => 'TOPIC', pod => $pod, value => 'feature:enhanced_xx' }
        if $contents =~ /feature\s+['"]enhanced_xx['"]/;
}

my %identity_ordinal;
for my $record (@records) {
    my $key = join "\0", @{$record}{qw(pod type value)};
    my $ordinal = ++$identity_ordinal{$key};
    $record->{source_ordinal} = $ordinal;
    $record->{source_identity} = 'sha256:' . sha256_hex(
        join "\0", $record->{pod}, $record->{type}, $record->{value}, $ordinal);
}

my %summary;
$summary{lc($_->{type}) . '_rows'}++ for @records;
$summary{total_rows} = scalar @records;

if (defined $capability_map) {
    my $reconciliation = check_capability_map($capability_map, \@pods,
        \@records, \%summary, \%pod_sha256);
    for my $record (@records) {
        $record->{capability} = $reconciliation->{$record->{source_identity}}
            if exists $reconciliation->{$record->{source_identity}};
    }
}

my $contents;
if ($format eq 'json') {
    my $inventory = {
        schema_version => 2,
        policy => 'current selected perl5 checkout; stable row identities; no pinned revision',
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
    my ($path, $expected_pods, $records, $summary, $pod_sha256) = @_;
    my $map = JSON::PP->new->decode(read_file($path));
    return check_semantic_capability_map($map, $expected_pods, $records,
        $pod_sha256) if ($map->{schema_version} // 0) == 2;
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
    return {};
}

sub check_semantic_capability_map {
    my ($map, $expected_pods, $records, $pod_sha256) = @_;
    die "Semantic capability map must not use a count-only inventory_contract\n"
        if exists $map->{inventory_contract};
    die "Capability map must target current Perl without a pinned revision\n"
        unless ($map->{policy} // '') =~ /current/i
            && ($map->{policy} // '') =~ /no pinned revision/i;
    die "Capability map POD list differs from the extractor POD list\n"
        unless JSON::PP->new->canonical->encode($map->{pod_files} // []) eq
            JSON::PP->new->canonical->encode($expected_pods);

    my %declared_source;
    my $sources = $map->{source_files};
    die "Semantic capability map source_files must cover every POD\n"
        unless ref($sources) eq 'ARRAY' && @$sources == @$expected_pods;
    for my $source (@$sources) {
        my ($pod, $sha) = @{$source}{qw(pod sha256)};
        die "Capability map has duplicate or unknown source identity '$pod'\n"
            unless defined($pod) && exists($pod_sha256->{$pod})
                && !$declared_source{$pod}++;
        die "Capability map has stale source identity for $pod\n"
            unless ($sha // '') eq $pod_sha256->{$pod};
    }
    die "Semantic capability map source_files omit a POD\n"
        if grep { !$declared_source{$_} } @$expected_pods;

    my (%family, %family_used);
    my $families = $map->{families};
    die "Capability map families must be a non-empty array\n"
        unless ref($families) eq 'ARRAY' && @$families;
    for my $entry (@$families) {
        my $id = $entry->{id} // '';
        die "Capability family has an invalid or duplicate id '$id'\n"
            unless $id =~ /\A[a-z0-9]+(?:-[a-z0-9]+)*\z/
                && !$family{$id};
        my $status = $entry->{status} // '';
        die "Capability family $id has invalid status '$status'\n"
            unless $status =~ /\A(?:implemented|partial|missing|not-applicable)\z/;
        my $evidence = $entry->{evidence};
        die "Capability family $id has no concrete evidence\n"
            unless ref($evidence) eq 'ARRAY' && @$evidence;
        my %kind;
        for my $item (@$evidence) {
            die "Capability family $id has malformed evidence\n"
                unless ref($item) eq 'HASH' && ($item->{kind} // '') =~
                    /\A(?:source|test|gap|architecture)\z/
                    && length($item->{path} // '')
                    && length($item->{note} // '');
            validate_project_paths($id, "$item->{kind} evidence", [$item->{path}]);
            $kind{$item->{kind}}++;
        }
        die "Implemented capability family $id lacks source and test evidence\n"
            if $status eq 'implemented' && (!$kind{source} || !$kind{test});
        die "Partial capability family $id lacks source, test, or gap evidence\n"
            if $status eq 'partial'
                && (!$kind{source} || !$kind{test} || !$kind{gap});
        die "Missing capability family $id lacks an in-progress gap record\n"
            if $status eq 'missing'
                && (!$kind{gap} || ($entry->{implementation_state} // '')
                    ne 'in-progress');
        die "Not-applicable capability family $id lacks architecture evidence\n"
            if $status eq 'not-applicable' && !$kind{architecture};
        $family{$id} = $entry;
    }

    my %excluded = map { $_ => 1 } @{ $map->{excluded_headings} // [] };
    my $rules = $map->{mapping_rules};
    die "Semantic capability map mapping_rules must be a non-empty array\n"
        unless ref($rules) eq 'ARRAY' && @$rules;
    my (%rule_id, %rule_used);
    for my $rule (@$rules) {
        my $id = $rule->{id} // '';
        die "Capability mapping rule has invalid or duplicate id '$id'\n"
            unless $id =~ /\A[a-z0-9]+(?:-[a-z0-9]+)*\z/ && !$rule_id{$id}++;
        die "Capability mapping rule $id references unknown family\n"
            unless $family{$rule->{family_id} // ''};
        die "Capability mapping rule $id is a count-only placeholder\n"
            if $rule->{match_all} || (!exists($rule->{values})
                && !exists($rule->{value_pattern}));
        die "Capability mapping rule $id has no record types\n"
            unless ref($rule->{types}) eq 'ARRAY' && @{$rule->{types}};
        if (exists $rule->{values}) {
            die "Capability mapping rule $id values must be non-empty\n"
                unless ref($rule->{values}) eq 'ARRAY' && @{$rule->{values}};
        }
        if (exists $rule->{value_pattern}) {
            my $compiled = eval { qr/$rule->{value_pattern}/ };
            die "Capability mapping rule $id has invalid value_pattern\n"
                unless $compiled;
            $rule->{_compiled_pattern} = $compiled;
        }
    }

    my %reconciliation;
    for my $record (@$records) {
        if ($record->{type} eq 'FILE') {
            next;
        }
        if ($record->{type} eq 'HEADING' && $excluded{$record->{value}}) {
            $reconciliation{$record->{source_identity}} = {
                status => 'excluded', reason => 'documentation-only heading',
            };
            next;
        }
        my @matches = grep { rule_matches($_, $record) } @$rules;
        die "Unmapped POD row $record->{source_identity} $record->{pod} "
            . "$record->{type} '$record->{value}'\n" unless @matches;
        die "Duplicate/conflicting POD mappings for $record->{source_identity} "
            . "$record->{pod} $record->{type} '$record->{value}': "
            . join(', ', map { $_->{id} } @matches) . "\n" if @matches > 1;
        my $rule = $matches[0];
        my $capability = $family{$rule->{family_id}};
        $rule_used{$rule->{id}}++;
        $family_used{$capability->{id}}++;
        $reconciliation{$record->{source_identity}} = {
            family_id => $capability->{id},
            status => $capability->{status},
            evidence => [map { $_->{path} } @{$capability->{evidence}}],
        };
    }
    die "Capability map has stale unused mapping rules: "
        . join(', ', sort grep { !$rule_used{$_} } keys %rule_id) . "\n"
        if grep { !$rule_used{$_} } keys %rule_id;
    die "Capability map has unused evidence families: "
        . join(', ', sort grep { !$family_used{$_} } keys %family) . "\n"
        if grep { !$family_used{$_} } keys %family;

    my $mapped = grep { ($_->{status} // '') ne 'excluded' }
        values %reconciliation;
    my $excluded_count = grep { ($_->{status} // '') eq 'excluded' }
        values %reconciliation;
    print STDERR "Semantic capability map check passed: $mapped mapped rows; "
        . "$excluded_count excluded headings; " . scalar(@$families)
        . " evidence families\n";
    return \%reconciliation;
}

sub rule_matches {
    my ($rule, $record) = @_;
    return 0 unless grep { $_ eq $record->{type} } @{$rule->{types}};
    return 0 if ref($rule->{pods}) eq 'ARRAY'
        && !grep { $_ eq $record->{pod} } @{$rule->{pods}};
    if (exists $rule->{values}) {
        return 0 unless grep { $_ eq $record->{value} } @{$rule->{values}};
    }
    return 0 if $rule->{_compiled_pattern}
        && $record->{value} !~ $rule->{_compiled_pattern};
    return 1;
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
