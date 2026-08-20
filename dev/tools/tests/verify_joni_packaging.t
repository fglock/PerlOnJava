use strict;
use warnings;

use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use JSON::PP;
use Test::More;

my $root = File::Spec->rel2abs(File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $tool = File::Spec->catfile($root, 'dev', 'tools', 'verify-joni-packaging.pl');
my $temporary = tempdir(CLEANUP => 1);
my $joni_ref = 'pkg:maven/org.jruby.joni/joni@2.2.7?type=jar';
my $jcodings_ref = 'pkg:maven/org.jruby.jcodings/jcodings@1.0.64?type=jar';

subtest 'green artifact has relocated classes, exact notices, and SBOM edge' => sub {
    my ($jar, $sbom) = fixture('green');
    my ($status, $output) = run_tool($jar, $sbom);
    is($status, 0, 'green artifact verifies');
    like($output, qr/verification passed/, 'green artifact reports success');
};

subtest 'packaging entry failure families are fail-closed' => sub {
    my ($missing_notice_jar, $sbom) = fixture('missing-notice', omit_notice => 1);
    rejected($missing_notice_jar, $sbom, qr/missing joni-LICENSE/, 'absent notice');
    my ($wrong_notice_jar, $same_sbom) = fixture('wrong-notice', wrong_notice => 1);
    rejected($wrong_notice_jar, $same_sbom, qr/notice bytes differ/, 'wrong notice bytes');
    my ($unrelocated_jar, $another_sbom) = fixture('unrelocated', unrelocated => 1);
    rejected($unrelocated_jar, $another_sbom, qr/unrelocated Joni classes/, 'unrelocated class');
};

subtest 'SBOM failure families are fail-closed' => sub {
    my ($jar, $sbom) = fixture('wrong-version', sbom => sub { $_[0]{components}[0]{version} = '9.9.9' });
    rejected($jar, $sbom, qr/wrong joni version/, 'wrong Joni version');
    ($jar, $sbom) = fixture('wrong-jcodings-version', sbom => sub { $_[0]{components}[1]{version} = '9.9.9' });
    rejected($jar, $sbom, qr/wrong jcodings version/, 'wrong JCodings version');
    ($jar, $sbom) = fixture('missing-joni', sbom => sub { shift @{$_[0]{components}} });
    rejected($jar, $sbom, qr/missing vendored joni/, 'missing Joni component');
    ($jar, $sbom) = fixture('duplicate-purl', sbom => sub { push @{$_[0]{components}}, {
        type => 'library', 'bom-ref' => 'other', purl => $joni_ref,
        group => 'example', name => 'other', version => '1' } });
    rejected($jar, $sbom, qr/duplicate purl/, 'duplicate purl');
    ($jar, $sbom) = fixture('missing-edge', sbom => sub { $_[0]{dependencies}[0]{dependsOn} = [] });
    rejected($jar, $sbom, qr/missing Joni -> JCodings dependency edge/, 'missing dependency edge');
    ($jar, $sbom) = fixture('duplicate-relation', sbom => sub { push @{$_[0]{dependencies}}, { ref => $joni_ref, dependsOn => [$jcodings_ref] } });
    rejected($jar, $sbom, qr/duplicate Joni dependency relations/, 'duplicate dependency relation');
    ($jar, $sbom) = fixture('bad-relation', sbom => sub { $_[0]{dependencies}[0]{dependsOn} = {} });
    rejected($jar, $sbom, qr/dependency relation is malformed/, 'non-array dependency relation');
    ($jar, $sbom) = fixture('malformed-json');
    write_file($sbom, '{ malformed');
    rejected($jar, $sbom, qr/Malformed SBOM JSON/, 'malformed SBOM');
};

done_testing;

sub fixture {
    my ($name, %option) = @_;
    my $tree = File::Spec->catdir($temporary, $name, 'tree');
    make_path(File::Spec->catdir($tree, 'org', 'perlonjava', 'internal', 'joni'));
    make_path(File::Spec->catdir($tree, 'org', 'perlonjava', 'internal', 'jcodings'));
    make_path(File::Spec->catdir($tree, 'META-INF', 'licenses'));
    write_file(File::Spec->catfile($tree, 'org', 'perlonjava', 'internal', 'joni', 'Regex.class'), 'joni');
    write_file(File::Spec->catfile($tree, 'org', 'perlonjava', 'internal', 'jcodings', 'Encoding.class'), 'jcodings');
    if ($option{unrelocated}) {
        make_path(File::Spec->catdir($tree, 'org', 'joni'));
        write_file(File::Spec->catfile($tree, 'org', 'joni', 'Regex.class'), 'bad');
    }
    my %source = (
        'joni-LICENSE.txt' => File::Spec->catfile($root, 'third_party', 'joni', 'LICENSE'),
        'jcodings-LICENSE.txt' => File::Spec->catfile($root, 'third_party', 'licenses', 'jcodings-LICENSE.txt'),
        'joni-PERLONJAVA-NOTICE.md' => File::Spec->catfile($root, 'third_party', 'joni', 'PERLONJAVA-NOTICE.md'),
    );
    for my $notice (keys %source) {
        next if $option{omit_notice} && $notice eq 'joni-LICENSE.txt';
        my $bytes = read_file($source{$notice});
        $bytes = 'wrong' if $option{wrong_notice} && $notice eq 'joni-LICENSE.txt';
        write_file(File::Spec->catfile($tree, 'META-INF', 'licenses', $notice), $bytes);
    }
    my $jar = File::Spec->catfile($temporary, "$name.jar");
    system('jar', 'cf', $jar, '-C', $tree, '.');
    die "jar fixture failed" if $? != 0;
    my $document = {
        components => [
            { type => 'library', 'bom-ref' => $joni_ref, purl => $joni_ref,
                group => 'org.jruby.joni', name => 'joni', version => '2.2.7' },
            { type => 'library', 'bom-ref' => $jcodings_ref, purl => $jcodings_ref,
                group => 'org.jruby.jcodings', name => 'jcodings', version => '1.0.64' },
        ],
        dependencies => [{ ref => $joni_ref, dependsOn => [$jcodings_ref] }],
    };
    $option{sbom}->($document) if $option{sbom};
    my $sbom = File::Spec->catfile($temporary, "$name.json");
    write_file($sbom, JSON::PP->new->canonical->encode($document));
    return ($jar, $sbom);
}

sub rejected {
    my ($jar, $sbom, $expected, $name) = @_;
    my ($status, $output) = run_tool($jar, $sbom);
    isnt($status, 0, "$name is rejected");
    like($output, $expected, "$name has specific diagnostic");
}

sub run_tool {
    pipe my $read, my $write or die "pipe: $!";
    my $pid = fork();
    die "fork: $!" unless defined $pid;
    if ($pid == 0) {
        close $read;
        open STDOUT, '>&', $write or die $!;
        open STDERR, '>&', $write or die $!;
        exec { $^X } $^X, $tool, @_[0, 1];
        die "exec: $!";
    }
    close $write;
    my $output = do { local $/; <$read> };
    close $read;
    waitpid($pid, 0);
    return ($? >> 8, $output);
}

sub write_file {
    my ($file, $contents) = @_;
    open my $fh, '>:raw', $file or die "Cannot write $file: $!";
    print {$fh} $contents;
    close $fh or die "Cannot close $file: $!";
}

sub read_file {
    my ($file) = @_;
    open my $fh, '<:raw', $file or die "Cannot read $file: $!";
    my $contents = do { local $/; <$fh> };
    close $fh or die "Cannot close $file: $!";
    return $contents;
}
