use strict;
use warnings;

use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use JSON::PP;
use Test::More;

my $root = File::Spec->rel2abs(
    File::Spec->catdir($FindBin::Bin, '..', '..', '..', '..'));
my $tool = File::Spec->catfile($root, 'dev', 'regex', 'tools',
    'extract_regex_pod_inventory.pl');
my $temporary = tempdir(CLEANUP => 1);
my $perl_root = File::Spec->catdir($temporary, 'perl5');
my $pod_root = File::Spec->catdir($perl_root, 'pod');
make_path($pod_root);

my @pods = qw(
    perlreref.pod
    perlrecharclass.pod
    perlrequick.pod
    perlrepository.pod
    perlre.pod
    perlretut.pod
    perlrebackslash.pod
);
for my $pod (@pods) {
    write_file(File::Spec->catfile($pod_root, $pod), <<'POD');
=head1 NAME

=head1 DESCRIPTION
POD
}
write_file(File::Spec->catfile($pod_root, 'perlreref.pod'), <<'POD');
=head1 NAME

=head1 DESCRIPTION

=head2 Recursive patterns

    (?R)
POD

my $map = {
    schema_version => 1,
    policy => 'current latest upstream perl5 checkout; no pinned revision',
    pod_files => \@pods,
    inventory_contract => {
        file_rows => 7,
        heading_rows => 15,
        construct_rows => 1,
        total_rows => 23,
        excluded_heading_rows => 14,
        mapped_capability_rows => 2,
    },
    excluded_headings => [qw(NAME DESCRIPTION)],
    families => [{
        id => 'calls-recursion-conditions',
        pod_topics => ['recursive patterns'],
        focused_tests => ['dev/regex/tools/tests/extract_regex_pod_inventory.t'],
        imported_tests => ['re/regexp.t'],
        source_evidence => ['dev/regex/tools/extract_regex_pod_inventory.pl'],
    }],
};
my $json = JSON::PP->new->canonical->pretty;
my $map_path = File::Spec->catfile($temporary, 'map.json');
my $inventory_path = File::Spec->catfile($temporary, 'inventory.json');
write_file($map_path, $json->encode($map));

my @command = ($^X, $tool,
    '--perl-root', $perl_root,
    '--format', 'json',
    '--output', $inventory_path,
    '--check-capability-map', $map_path);
system @command;
is($? >> 8, 0, 'inventory and capability-map check succeeds');

my $inventory = $json->decode(read_file($inventory_path));
is_deeply($inventory->{summary}, {
        file_rows => 7,
        heading_rows => 15,
        construct_rows => 1,
        total_rows => 23,
    }, 'JSON summary counts every record type');
is_deeply($inventory->{pod_files}, \@pods,
    'all seven required POD files are ordered explicitly');
is(scalar(grep {
        $_->{type} eq 'CONSTRUCT'
            && $_->{pod} eq 'perlreref.pod'
            && $_->{value} eq '(?R'
    } @{ $inventory->{records} }), 1,
    'JSON records retain source POD provenance');

my $first = read_file($inventory_path);
system @command;
is($? >> 8, 0, 'second extraction succeeds');
is(read_file($inventory_path), $first,
    'second extraction is byte-identical');

my $tsv_path = File::Spec->catfile($temporary, 'inventory.tsv');
system $^X, $tool, '--perl-root', $perl_root,
    '--format', 'tsv', '--output', $tsv_path;
is($? >> 8, 0, 'legacy TSV extraction succeeds');
my @tsv = grep { length } split /\n/, read_file($tsv_path);
is(scalar @tsv, 23, 'TSV contains the same complete inventory');
is($tsv[0], 'FILE' . "\t" . 'perlreref.pod',
    'TSV ordering remains backward compatible');

$map->{inventory_contract}{total_rows} = 22;
my $stale_map = File::Spec->catfile($temporary, 'stale-map.json');
write_file($stale_map, $json->encode($map));
system $^X, $tool, '--perl-root', $perl_root,
    '--output', File::Spec->catfile($temporary, 'stale.tsv'),
    '--check-capability-map', $stale_map;
isnt($? >> 8, 0, 'inventory drift fails the capability-map check');

done_testing;

sub write_file {
    my ($path, $contents) = @_;
    open my $fh, '>:raw', $path or die "Cannot write $path: $!\n";
    print {$fh} $contents;
    close $fh or die "Cannot close $path: $!\n";
}

sub read_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!\n";
    my $contents = do { local $/; <$fh> };
    close $fh or die "Cannot close $path: $!\n";
    return $contents;
}
