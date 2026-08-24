use strict;
use warnings;

use Digest::SHA qw(sha256_hex);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use IPC::Open3;
use JSON::PP;
use Symbol qw(gensym);
use Test::More;

my $repository = File::Spec->rel2abs(
    File::Spec->catdir($FindBin::Bin, '..', '..', '..', '..'));
my $tool = File::Spec->catfile($repository, 'dev', 'regex', 'tools',
    'extract_regex_pod_inventory.pl');
my $checked_map = File::Spec->catfile($repository, 'dev', 'regex', 'tools',
    'regex_pod_capability_map.json');
my $json = JSON::PP->new->canonical->pretty;
my @pods = qw(
    perlreref.pod
    perlrecharclass.pod
    perlrequick.pod
    perlrepository.pod
    perlre.pod
    perlretut.pod
    perlrebackslash.pod
);

subtest 'checked-in semantic dispositions match the current audit boundary' => sub {
    my $map = decode_file($checked_map);
    is($map->{schema_version}, 2, 'checked-in map uses semantic schema');
    ok(!exists($map->{inventory_contract}),
        'checked-in map has no arithmetic count placeholder');
    my %family = map { $_->{id} => $_ } @{$map->{families}};
    is($family{'enhanced-xx'}{status}, 'implemented',
        'enhanced_xx is integrated with source and test evidence');
    ok(!exists($family{'enhanced-xx'}{implementation_state}),
        'enhanced_xx no longer carries an in-progress marker');
    is($family{'direct-keep-in-lookaround'}{status}, 'partial',
        'direct KEEP uses the map compatibility status for its divergence');
    is($family{'custom-c-regex-engine'}{status}, 'not-applicable',
        'Perl internal C regex engine ABI is explicitly not applicable');
};

subtest 'clean fixture maps every row with stable identity and evidence' => sub {
    my ($perl_root, $map) = fixture();
    my ($status, $stdout, $stderr) = run_check($perl_root, $map);
    is($status, 0, 'clean semantic fixture passes');
    like($stderr, qr/Semantic capability map check passed/,
        'clean fixture reports semantic reconciliation');
    my $inventory = $json->decode($stdout);
    is($inventory->{schema_version}, 2,
        'semantic inventory version identifies stable row records');
    my @mapped = grep { $_->{type} =~ /\A(?:HEADING|CONSTRUCT|TOPIC)\z/
            && ($_->{capability}{status} // '') ne 'excluded' }
        @{$inventory->{records}};
    ok(@mapped, 'fixture has mapped semantic rows');
    ok(!(grep { ($_->{source_identity} // '') !~ /\Asha256:[0-9a-f]{64}\z/
            || !@{$_->{capability}{evidence} // []} } @mapped),
        'every mapped row carries stable identity and concrete evidence');
};

subtest 'fail-closed semantic fixtures' => sub {
    my ($perl_root, $base) = fixture();
    my @cases = (
        ['unmapped', sub { pop @{$_[0]{mapping_rules}} }, qr/Unmapped POD row/],
        ['duplicate', sub {
            push @{$_[0]{mapping_rules}}, {
                id => 'duplicate-recursion', family_id => 'recursion',
                types => ['HEADING'], values => ['Recursive patterns'],
            };
        }, qr/Duplicate\/conflicting POD mappings/],
        ['missing-evidence', sub {
            $_[0]{families}[0]{evidence}[0]{path} = 'does/not/exist.t';
        }, qr/references missing source evidence/],
        ['stale-identity', sub {
            $_[0]{source_files}[0]{sha256} = '0' x 64;
        }, qr/stale source identity/],
        ['status-transition', sub {
            $_[0]{families}[1]{status} = 'implemented';
        }, qr/Implemented capability family enhanced-xx lacks source and test evidence/],
        ['count-only', sub {
            $_[0]{inventory_contract} = { mapped_capability_rows => 3 };
        }, qr/must not use a count-only inventory_contract/],
    );
    for my $case (@cases) {
        my $map = clone($base);
        $case->[1]->($map);
        my ($status, undef, $stderr) = run_check($perl_root, $map);
        isnt($status, 0, "$case->[0] fixture fails");
        like($stderr, $case->[2], "$case->[0] has a specific diagnostic");
    }
};

done_testing;

sub fixture {
    my $temporary = tempdir(CLEANUP => 1);
    my $perl_root = File::Spec->catdir($temporary, 'perl5');
    my $pod_root = File::Spec->catdir($perl_root, 'pod');
    make_path($pod_root);
    my %contents = map { $_ => "=head1 NAME\n\n=head1 DESCRIPTION\n" } @pods;
    $contents{'perlreref.pod'} .= "\n=head2 Recursive patterns\n\n    (?R)\n";
    $contents{'perlre.pod'} .= "\nUse feature 'enhanced_xx' for this topic.\n";
    for my $pod (@pods) {
        write_file(File::Spec->catfile($pod_root, $pod), $contents{$pod});
    }
    my $map = {
        schema_version => 2,
        policy => 'current selected Perl checkout; no pinned revision',
        pod_files => \@pods,
        source_files => [map {
            { pod => $_, sha256 => sha256_hex($contents{$_}) }
        } @pods],
        excluded_headings => [qw(NAME DESCRIPTION)],
        families => [
            {
                id => 'recursion', status => 'implemented', evidence => [
                    { kind => 'source',
                      path => 'dev/regex/tools/extract_regex_pod_inventory.pl',
                      note => 'fixture source evidence' },
                    { kind => 'test',
                      path => 'dev/regex/tools/tests/regex_pod_capability_map_semantic.t',
                      note => 'fixture test evidence' },
                ],
            },
            {
                id => 'enhanced-xx', status => 'missing',
                implementation_state => 'in-progress', evidence => [
                    { kind => 'gap', path => 'src/main/perl/lib/feature.pm',
                      note => 'fixture missing-feature evidence' },
                ],
            },
        ],
        mapping_rules => [
            { id => 'recursion-heading', family_id => 'recursion',
              types => ['HEADING'], values => ['Recursive patterns'] },
            { id => 'recursion-construct', family_id => 'recursion',
              types => ['CONSTRUCT'], values => ['(?R'] },
            { id => 'enhanced-topic', family_id => 'enhanced-xx',
              types => ['TOPIC'], values => ['feature:enhanced_xx'] },
        ],
    };
    return ($perl_root, $map);
}

sub run_check {
    my ($perl_root, $map) = @_;
    my $temporary = tempdir(CLEANUP => 1);
    my $map_path = File::Spec->catfile($temporary, 'map.json');
    write_file($map_path, $json->encode($map));
    my $error = gensym;
    my $pid = open3(undef, my $stdout, $error, $^X, $tool,
        '--perl-root', $perl_root, '--format', 'json',
        '--check-capability-map', $map_path);
    local $/;
    my $output = <$stdout> // '';
    my $stderr = <$error> // '';
    waitpid($pid, 0);
    return ($? >> 8, $output, $stderr);
}

sub clone {
    return $json->decode($json->encode($_[0]));
}

sub decode_file {
    my ($path) = @_;
    return $json->decode(read_file($path));
}

sub read_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!\n";
    my $contents = do { local $/; <$fh> };
    close $fh or die "Cannot close $path: $!\n";
    return $contents;
}

sub write_file {
    my ($path, $contents) = @_;
    my (undef, $directory) = File::Spec->splitpath($path);
    make_path($directory);
    open my $fh, '>:raw', $path or die "Cannot write $path: $!\n";
    print {$fh} $contents;
    close $fh or die "Cannot close $path: $!\n";
}
