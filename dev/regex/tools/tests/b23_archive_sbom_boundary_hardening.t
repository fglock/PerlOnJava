use strict;
use warnings;

use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use IO::Compress::Zip qw($ZipError);
use JSON::PP;
use Test::More;

use lib "$FindBin::Bin/../lib";
use PerlOnJava::CpanJarSbom qw(inspect_jar_sbom);

my $tmp = tempdir(CLEANUP => 1);
my $commit = '9d73ce7159d3c818a694b77988d6581a2636f560';
my $joni_ref = 'pkg:generic/perlonjava/joni-fork@2.2.7';
my $jcodings_ref = 'pkg:maven/org.jruby.jcodings/jcodings@1.0.64?type=jar';
my $json = JSON::PP->new->utf8->canonical;

my $valid_sbom = sbom_fixture();
my ($valid_jar, $valid_sbom_path) = fixture('valid', $valid_sbom);
my $result = inspect_jar_sbom($valid_jar, $valid_sbom_path, $commit);
is($result->{jar_embedded_commit}, $commit,
    'producer-shaped JAR and SBOM fixture is accepted');

for my $case (
    ['absolute slash', '/absolute.class'],
    ['drive path', 'C:/drive.class'],
    ['UNC path', '//server/share.class'],
    ['backslash', 'bad\\entry.class'],
    ['dot component', 'safe/./entry.class'],
    ['parent component', 'safe/../entry.class'],
    ['empty component', 'safe//entry.class'],
    ['NUL', "safe/nul\0entry.class"],
) {
    my ($jar, $sbom) = fixture("archive-$case->[0]", $valid_sbom,
        extra_names => [$case->[1]]);
    rejected($jar, $sbom, qr/unsafe member name/, "$case->[0] member");
}

{
    my ($jar, $sbom) = fixture('archive-duplicate', $valid_sbom,
        extra_names => ['duplicate.class', 'duplicate.class']);
    rejected($jar, $sbom, qr/duplicate member/, 'duplicate archive member');
}

{
    my ($jar, $sbom) = fixture('archive-symlink', $valid_sbom,
        extra_names => ['link.class'], symlink_name => 'link.class');
    rejected($jar, $sbom, qr/symlink-like metadata/,
        'Unix symlink metadata on an archive member');
}

for my $case (
    ['Joni optional type', sub { $_[0]{components}[0]{type} = 'application' },
        qr/wrong joni-fork type/],
    ['Joni explicit version', sub { $_[0]{components}[0]{version} = '2.2.8' },
        qr/wrong joni-fork version/],
    ['JCodings explicit version', sub { $_[0]{components}[1]{version} = '1.0.65' },
        qr/wrong jcodings version/],
    ['Joni purl', sub { $_[0]{components}[0]{purl} = 'pkg:generic/example/joni-fork@2.2.7' },
        qr/wrong joni-fork purl/],
    ['JCodings purl', sub { $_[0]{components}[1]{purl} = 'pkg:maven/example/jcodings@1.0.64' },
        qr/wrong jcodings purl/],
    ['Joni license', sub { $_[0]{components}[0]{licenses}[0]{license}{id} = 'Apache-2.0' },
        qr/wrong or missing joni-fork license/],
    ['JCodings license', sub { $_[0]{components}[1]{licenses} = [] },
        qr/wrong or missing jcodings license/],
) {
    sbom_rejected(@$case);
}

sbom_rejected('duplicate optional vendored authorship property', sub {
    push @{$_[0]{components}[0]{properties}},
        { name => 'perlonjava:vendored', value => 'true' };
}, qr/missing or duplicate perlonjava:vendored property/);

sbom_rejected('duplicate selected-source commit property', sub {
    push @{$_[0]{components}[0]{properties}},
        { name => 'perlonjava:source-commit', value => $commit };
}, qr/missing or duplicate perlonjava:source-commit property/);

for my $property (
    ['perlonjava:vendored', 'false'],
    ['perlonjava:modified', 'false'],
    ['perlonjava:vendored-source-path', 'other/joni'],
    ['perlonjava:source-commit', 'a' x 40],
    ['perlonjava:upstream-maven-coordinate', 'org.jruby.joni:joni:2.2.8'],
    ['perlonjava:upstream-tag', 'joni-2.2.8'],
    ['perlonjava:upstream-commit', 'b' x 40],
) {
    my ($name, $wrong) = @$property;
    sbom_rejected("wrong $name", sub {
        my ($entry) = grep { $_->{name} eq $name }
            @{$_[0]{components}[0]{properties}};
        $entry->{value} = $wrong;
    }, qr/wrong \Q$name\E property/);
}

sbom_rejected('duplicate Joni identity', sub {
    my $copy = clone($_[0]{components}[0]);
    $copy->{'bom-ref'} = $copy->{purl} = 'pkg:generic/example/joni-fork@2.2.7';
    push @{$_[0]{components}}, $copy;
}, qr/duplicate joni-fork components/);

sbom_rejected('duplicate canonical bom-ref', sub {
    push @{$_[0]{components}}, {
        type => 'library', group => 'example', name => 'collision', version => '1',
        'bom-ref' => $joni_ref, purl => 'pkg:generic/example/collision@1',
    };
}, qr/duplicate bom-ref/);

sbom_rejected('missing root edge', sub {
    $_[0]{dependencies}[0]{dependsOn} = [];
}, qr/dependency relation is missing/);

sbom_rejected('duplicate root relation', sub {
    push @{$_[0]{dependencies}}, clone($_[0]{dependencies}[0]);
}, qr/dependency source is missing or duplicated/);

sbom_rejected('duplicate Joni to JCodings edge', sub {
    push @{$_[0]{dependencies}[1]{dependsOn}}, $jcodings_ref;
}, qr/dependency relation is duplicated/);

done_testing;

sub sbom_fixture {
    return {
        '$schema' => 'http://cyclonedx.org/schema/bom-1.6.schema.json',
        bomFormat => 'CycloneDX', specVersion => '1.6',
        serialNumber => 'urn:uuid:00000000-0000-4000-8000-000000000000',
        version => 1,
        metadata => { component => {
            type => 'application', 'bom-ref' => 'perlonjava',
            name => 'perlonjava', version => '5.44.0',
            purl => 'pkg:generic/perlonjava@5.44.0',
            licenses => [{ license => { id => 'Artistic-2.0' } }],
        } },
        components => [
            {
                type => 'library', 'bom-ref' => $joni_ref,
                group => 'org.perlonjava.fork', name => 'joni-fork',
                version => '2.2.7', purl => $joni_ref,
                description => 'PerlOnJava-maintained source fork of Joni',
                licenses => [{ license => { id => 'MIT' } }],
                externalReferences => [
                    { type => 'website', url => 'https://github.com/jruby/joni' },
                    { type => 'vcs', url => 'https://github.com/jruby/joni.git' },
                ],
                properties => [
                    { name => 'perlonjava:vendored', value => 'true' },
                    { name => 'perlonjava:modified', value => 'true' },
                    { name => 'perlonjava:vendored-source-path', value => 'third_party/joni' },
                    { name => 'perlonjava:source-commit', value => $commit },
                    { name => 'perlonjava:upstream-maven-coordinate', value => 'org.jruby.joni:joni:2.2.7' },
                    { name => 'perlonjava:upstream-tag', value => 'joni-2.2.7' },
                    { name => 'perlonjava:upstream-commit', value => '57fd57b4f977813a7b4b35e0179943b1f06f51d7' },
                ],
            },
            {
                type => 'library', 'bom-ref' => $jcodings_ref,
                group => 'org.jruby.jcodings', name => 'jcodings',
                version => '1.0.64', purl => $jcodings_ref,
                licenses => [{ license => { id => 'MIT' } }],
            },
        ],
        dependencies => [
            { ref => 'perlonjava', dependsOn => [$joni_ref, $jcodings_ref] },
            { ref => $joni_ref, dependsOn => [$jcodings_ref] },
        ],
    };
}

sub fixture {
    my ($name, $document, %option) = @_;
    $name =~ s/[^A-Za-z0-9_.-]+/-/g;
    my $sbom_path = File::Spec->catfile($tmp, "$name.sbom.json");
    my $jar_path = File::Spec->catfile($tmp, "$name.jar");
    my $sbom_bytes = $json->encode($document);
    write_raw($sbom_path, $sbom_bytes);
    my @members = (
        ['org/perlonjava/core/Configuration.class', "commit $commit\n"],
        ['META-INF/sbom/sbom.json', $sbom_bytes],
        ['org/perlonjava/internal/joni/Regex.class', "joni\n"],
        ['org/perlonjava/internal/jcodings/Encoding.class', "jcodings\n"],
        (map { [$_, "extra\n"] } @{$option{extra_names} // []}),
    );
    my $zip = IO::Compress::Zip->new($jar_path, Name => $members[0][0])
        or die "Cannot create fixture JAR: $ZipError";
    print {$zip} $members[0][1];
    for my $member (@members[1 .. $#members]) {
        $zip->newStream(Name => $member->[0])
            or die "Cannot add fixture member: $ZipError";
        print {$zip} $member->[1];
    }
    $zip->close or die "Cannot close fixture JAR: $ZipError";
    mark_central_member_as_symlink($jar_path, $option{symlink_name})
        if defined $option{symlink_name};
    return ($jar_path, $sbom_path);
}

sub mark_central_member_as_symlink {
    my ($path, $wanted) = @_;
    my $bytes = read_raw($path);
    my $offset = 0;
    while (($offset = index($bytes, "PK\001\002", $offset)) >= 0) {
        die 'Truncated central directory fixture' if $offset + 46 > length($bytes);
        my ($name_length, $extra_length, $comment_length) =
            unpack('vvv', substr($bytes, $offset + 28, 6));
        my $name = substr($bytes, $offset + 46, $name_length);
        if ($name eq $wanted) {
            substr($bytes, $offset + 4, 2, pack('v', (3 << 8) | 20));
            substr($bytes, $offset + 38, 4, pack('V', 0120777 << 16));
            write_raw($path, $bytes);
            return;
        }
        $offset += 46 + $name_length + $extra_length + $comment_length;
    }
    die "Cannot find central member $wanted";
}

sub sbom_rejected {
    my ($name, $mutate, $expected) = @_;
    my $document = clone($valid_sbom);
    $mutate->($document);
    my ($jar, $sbom) = fixture("sbom-$name", $document);
    rejected($jar, $sbom, $expected, $name);
}

sub rejected {
    my ($jar, $sbom, $expected, $name) = @_;
    my $ok = eval { inspect_jar_sbom($jar, $sbom, $commit); 1 };
    ok(!$ok, "$name is rejected");
    like($@, $expected, "$name has a fail-closed diagnostic");
}

sub clone { JSON::PP->new->decode(JSON::PP->new->encode($_[0])) }

sub read_raw {
    open my $fh, '<:raw', $_[0] or die "Cannot read $_[0]: $!";
    local $/;
    my $bytes = <$fh>;
    close $fh or die "Cannot close $_[0]: $!";
    return $bytes;
}

sub write_raw {
    open my $fh, '>:raw', $_[0] or die "Cannot write $_[0]: $!";
    print {$fh} $_[1];
    close $fh or die "Cannot close $_[0]: $!";
}
