#!/usr/bin/env perl

use strict;
use warnings;

use File::Find qw(find);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin qw($Bin);
use IO::Compress::Zip qw($ZipError);
use JSON::PP;
use Test::More;

my $root = File::Spec->rel2abs(File::Spec->catdir($Bin, '..', '..', '..', '..'));
my $merge = File::Spec->catfile($root, 'dev', 'tools', 'merge-sbom.pl');
my $packaging = File::Spec->catfile($root, 'dev', 'regex', 'tools',
    'verify-joni-packaging.pl');
my $notice = File::Spec->catfile($root, 'dev', 'regex', 'tools',
    'verify_notice_license.pl');
my $acceptance = File::Spec->catfile($root, 'dev', 'regex', 'tools',
    'run_regex_acceptance.pl');
my $temporary = tempdir(CLEANUP => 1);
my $fork_ref = 'pkg:generic/perlonjava/joni-fork@2.2.7';
my $legacy_ref = 'pkg:maven/org.jruby.joni/joni@2.2.7?type=jar';
my $jcodings_ref = 'pkg:maven/org.jruby.jcodings/jcodings@1.0.64?type=jar';
my $source_commit = '1234567890abcdef1234567890abcdef12345678';
my $output_counter = 0;

my $merged = merged_sbom();
my ($fork) = grep { ($_->{'bom-ref'} // '') eq $fork_ref }
    @{$merged->{components}};
ok($fork, 'merger emits a generic PerlOnJava Joni fork component');
is($fork->{group}, 'org.perlonjava.fork', 'fork uses a non-upstream group');
is($fork->{name}, 'joni-fork', 'fork uses a non-colliding name');
ok(!(grep { ($_->{'bom-ref'} // '') eq $legacy_ref
        || ($_->{purl} // '') eq $legacy_ref } @{$merged->{components}}),
    'merger does not claim the upstream Maven Joni artifact identity');
is_deeply(properties($fork), {
    'perlonjava:modified' => 'true',
    'perlonjava:source-commit' => $source_commit,
    'perlonjava:upstream-commit' =>
        '57fd57b4f977813a7b4b35e0179943b1f06f51d7',
    'perlonjava:upstream-maven-coordinate' => 'org.jruby.joni:joni:2.2.7',
    'perlonjava:upstream-tag' => 'joni-2.2.7',
    'perlonjava:vendored' => 'true',
    'perlonjava:vendored-source-path' => 'third_party/joni',
}, 'merger emits exact fork provenance properties');
my ($jcodings) = grep { ($_->{'bom-ref'} // '') eq $jcodings_ref }
    @{$merged->{components}};
ok($jcodings, 'unmodified JCodings keeps its Maven identity');
my ($fork_relation) = grep { ($_->{ref} // '') eq $fork_ref }
    @{$merged->{dependencies}};
is_deeply($fork_relation->{dependsOn}, [$jcodings_ref],
    'dependency relation is keyed by the fork ref');

subtest 'both verifiers accept one byte-identical embedded merged SBOM' => sub {
    my ($source, $jar, $sbom) = fixture('green', $merged);
    accepted_by_both($source, $jar, $sbom);
};

subtest 'strict production schema is explicit and default remains fixture-only' => sub {
    my ($source, $jar, $sbom) = fixture('explicit-strict', $merged);
    my ($status, $text) = capture($^X, $packaging, $jar, $sbom);
    isnt($status, 0, 'default packaging mode does not accept production fork schema');
    like($text, qr/missing vendored joni 2\.2\.7/,
        'default packaging mode retains the narrow legacy fixture schema');
    ($status, $text) = run_notice_default($source, $jar, $sbom);
    isnt($status, 0, 'default notice mode does not accept production fork schema');
    like($text, qr/missing joni component/,
        'default notice mode retains the narrow legacy fixture schema');
    like(read_file($acceptance),
        qr/command\s*=>\s*\[\$option\{perl\},\s*\$option\{packaging_tool\},\s*'--strict',/s,
        'Regex implementation acceptance invokes packaging verification in strict mode');
};

subtest 'embedded SBOM presence, uniqueness, and exact bytes are fail-closed' => sub {
    my ($source, $jar, $sbom) = fixture('missing-embedded', $merged,
        omit_embedded => 1);
    rejected_by_both($source, $jar, $sbom, qr/missing META-INF\/sbom\/sbom\.json/,
        'absent embedded SBOM');

    ($source, $jar, $sbom) = fixture('duplicate-embedded', $merged,
        duplicate_embedded => 1);
    rejected_by_both($source, $jar, $sbom,
        qr/duplicate META-INF\/sbom\/sbom\.json/,
        'duplicate embedded SBOM');

    ($source, $jar, $sbom) = fixture('mutated-embedded', $merged,
        embedded_bytes => "{\"mutated\":true}\n");
    rejected_by_both($source, $jar, $sbom,
        qr/embedded SBOM bytes differ from external merged SBOM/,
        'mutated embedded SBOM');
};

subtest 'legacy upstream identity and every required provenance field are fail-closed' => sub {
    my $legacy = clone($merged);
    my ($component) = grep { ($_->{'bom-ref'} // '') eq $fork_ref }
        @{$legacy->{components}};
    $component->{group} = 'org.jruby.joni';
    $component->{name} = 'joni';
    $component->{'bom-ref'} = $legacy_ref;
    $component->{purl} = $legacy_ref;
    replace_ref($legacy, $fork_ref, $legacy_ref);
    my ($source, $jar, $sbom) = fixture('legacy-identity', $legacy);
    rejected_by_both($source, $jar, $sbom,
        qr/claims the modified Joni fork is the upstream Maven artifact/,
        'upstream-only Joni identity');

    for my $property (sort keys %{properties($fork)}) {
        my $changed = clone($merged);
        my ($changed_fork) = grep { ($_->{'bom-ref'} // '') eq $fork_ref }
            @{$changed->{components}};
        my ($entry) = grep { ($_->{name} // '') eq $property }
            @{$changed_fork->{properties}};
        $entry->{value} = $property eq 'perlonjava:source-commit'
            ? 'not-a-full-sha' : 'wrong';
        ($source, $jar, $sbom) = fixture("wrong-$property", $changed);
        my $diagnostic = $property eq 'perlonjava:vendored'
            ? qr/(?:wrong perlonjava:vendored property|missing Joni vendored authorship metadata)/
            : qr/wrong \Q$property\E property/;
        rejected_by_both($source, $jar, $sbom,
            $diagnostic,
            "wrong $property");
    }

    my $missing = clone($merged);
    my ($missing_fork) = grep { ($_->{'bom-ref'} // '') eq $fork_ref }
        @{$missing->{components}};
    pop @{$missing_fork->{properties}};
    ($source, $jar, $sbom) = fixture('missing-property', $missing);
    rejected_by_both($source, $jar, $sbom,
        qr/missing or duplicate perlonjava:upstream-commit property/,
        'missing provenance property');

    my $duplicate = clone($merged);
    my ($duplicate_fork) = grep { ($_->{'bom-ref'} // '') eq $fork_ref }
        @{$duplicate->{components}};
    push @{$duplicate_fork->{properties}},
        { name => 'perlonjava:upstream-tag', value => 'joni-2.2.7' };
    ($source, $jar, $sbom) = fixture('duplicate-property', $duplicate);
    rejected_by_both($source, $jar, $sbom,
        qr/missing or duplicate perlonjava:upstream-tag property/,
        'duplicate provenance property');
};

done_testing;

sub merged_sbom {
    my $java = {
        metadata => { component => { version => '5.44.1' } },
        components => [
            {
                type => 'library', group => 'org.jruby.jcodings',
                name => 'jcodings', version => '1.0.64',
                'bom-ref' => $jcodings_ref, purl => $jcodings_ref,
                licenses => [{ license => { id => 'MIT' } }],
            },
        ],
    };
    my $perl = {
        components => [
            {
                type => 'library', name => 'Test::More', version => '1.302199',
                'bom-ref' => 'perl:Test-More',
                purl => 'pkg:cpan/Test::More@1.302199',
            },
        ],
    };
    my $java_file = write_file(File::Spec->catfile($temporary, 'java.json'),
        JSON::PP->new->canonical->encode($java));
    my $perl_file = write_file(File::Spec->catfile($temporary, 'perl.json'),
        JSON::PP->new->canonical->encode($perl));
    local $ENV{PERLONJAVA_SOURCE_COMMIT} = $source_commit;
    my ($status, $text) = capture($^X, $merge, $java_file, $perl_file);
    is($status, 0, 'merger accepts valid Java and Perl BOM inputs')
        or diag $text;
    return JSON::PP->new->decode($text);
}

sub fixture {
    my ($name, $document, %option) = @_;
    $name =~ s/[^A-Za-z0-9_.-]+/-/g;
    my $base = File::Spec->catdir($temporary, $name);
    my $source = File::Spec->catdir($base, 'source');
    make_path(File::Spec->catdir($source, 'third_party', 'joni'));
    make_path(File::Spec->catdir($source, 'third_party', 'licenses'));
    my %notices = (
        'joni-LICENSE.txt' => ['third_party/joni/LICENSE',
            'third_party/joni/LICENSE'],
        'joni-PERLONJAVA-NOTICE.md' => ['third_party/joni/PERLONJAVA-NOTICE.md',
            'third_party/joni/PERLONJAVA-NOTICE.md'],
        'jcodings-LICENSE.txt' => ['third_party/licenses/jcodings-LICENSE.txt',
            'third_party/licenses/jcodings-LICENSE.txt'],
    );
    my %entries = (
        'org/perlonjava/internal/joni/Regex.class' => 'joni',
        'org/perlonjava/internal/jcodings/Encoding.class' => 'jcodings',
    );
    for my $entry (sort keys %notices) {
        my ($repository_path, $source_path) = @{$notices{$entry}};
        my $bytes = read_file(File::Spec->catfile($root, split m{/},
            $repository_path));
        write_file(File::Spec->catfile($source, split m{/}, $source_path), $bytes);
        $entries{"META-INF/licenses/$entry"} = $bytes;
    }
    my $sbom_bytes = JSON::PP->new->utf8->canonical->pretty->encode($document);
    my $sbom = write_file(File::Spec->catfile($base, 'sbom.json'), $sbom_bytes);
    $entries{'META-INF/sbom/sbom.json'} =
        $option{embedded_bytes} // $sbom_bytes unless $option{omit_embedded};
    my $jar = File::Spec->catfile($base, 'standalone.jar');
    create_zip($jar, \%entries,
        $option{duplicate_embedded} ? 'META-INF/sbom/sbom.json' : undef);
    return ($source, $jar, $sbom);
}

sub create_zip {
    my ($file, $entries, $duplicate) = @_;
    my @names = sort keys %$entries;
    push @names, $duplicate if defined $duplicate;
    my $first = shift @names;
    my $zip = IO::Compress::Zip->new($file, Name => $first)
        or die "Cannot create $file: $ZipError";
    print {$zip} $entries->{$first};
    for my $name (@names) {
        $zip->newStream(Name => $name)
            or die "Cannot add $name to $file: $ZipError";
        print {$zip} $entries->{$name};
    }
    close $zip or die "Cannot close $file: $ZipError";
}

sub accepted_by_both {
    my ($source, $jar, $sbom) = @_;
    my ($status, $text) = capture($^X, $packaging, '--strict', $jar, $sbom);
    is($status, 0, 'packaging verifier accepts fixture') or diag $text;
    ($status, $text) = run_notice($source, $jar, $sbom);
    is($status, 0, 'notice/license verifier accepts fixture') or diag $text;
}

sub rejected_by_both {
    my ($source, $jar, $sbom, $pattern, $label) = @_;
    my ($status, $text) = capture($^X, $packaging, '--strict', $jar, $sbom);
    isnt($status, 0, "packaging verifier rejects $label");
    like($text, $pattern, "packaging verifier diagnoses $label");
    ($status, $text) = run_notice($source, $jar, $sbom);
    isnt($status, 0, "notice/license verifier rejects $label");
    like($text, $pattern, "notice/license verifier diagnoses $label");
}

sub run_notice {
    my ($source, $jar, $sbom) = @_;
    my $output = File::Spec->catfile($temporary,
        sprintf('notice-output-%04d.json', ++$output_counter));
    return capture($^X, $notice, '--strict', '--source-root', $source, '--jar', $jar,
        '--sbom', $sbom, '--output', $output);
}

sub run_notice_default {
    my ($source, $jar, $sbom) = @_;
    my $output = File::Spec->catfile($temporary,
        sprintf('notice-output-%04d.json', ++$output_counter));
    return capture($^X, $notice, '--source-root', $source, '--jar', $jar,
        '--sbom', $sbom, '--output', $output);
}

sub properties {
    my ($component) = @_;
    return { map { ($_->{name} // '') => ($_->{value} // '') }
        @{$component->{properties} // []} };
}

sub replace_ref {
    my ($document, $old, $new) = @_;
    for my $relation (@{$document->{dependencies}}) {
        $relation->{ref} = $new if ($relation->{ref} // '') eq $old;
        for my $dependency (@{$relation->{dependsOn} // []}) {
            $dependency = $new if $dependency eq $old;
        }
    }
}

sub clone {
    return JSON::PP->new->decode(JSON::PP->new->canonical->encode($_[0]));
}

sub capture {
    my (@command) = @_;
    pipe my $read, my $write or die "pipe: $!";
    my $pid = fork();
    die "fork: $!" unless defined $pid;
    if ($pid == 0) {
        close $read;
        open STDOUT, '>&', $write or die $!;
        open STDERR, '>&', $write or die $!;
        exec { $command[0] } @command;
        die "exec: $!";
    }
    close $write;
    my $text = do { local $/; <$read> };
    close $read;
    waitpid($pid, 0);
    return ($? >> 8, $text);
}

sub write_file {
    my ($file, $bytes) = @_;
    my (undef, $directory) = File::Spec->splitpath($file);
    make_path($directory) unless -d $directory;
    open my $fh, '>:raw', $file or die "Cannot write $file: $!";
    print {$fh} $bytes;
    close $fh or die "Cannot close $file: $!";
    return $file;
}

sub read_file {
    my ($file) = @_;
    open my $fh, '<:raw', $file or die "Cannot read $file: $!";
    my $bytes = do { local $/; <$fh> };
    close $fh or die "Cannot close $file: $!";
    return $bytes;
}
