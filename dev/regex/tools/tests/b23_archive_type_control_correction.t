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
my $commit = '826eed4fdeb94e378aa4a543a32eda617ff74a10';
my $joni_ref = 'pkg:generic/perlonjava/joni-fork@2.2.7';
my $jcodings_ref = 'pkg:maven/org.jruby.jcodings/jcodings@1.0.64?type=jar';
my $sbom_bytes = JSON::PP->new->utf8->canonical->encode({
    bomFormat => 'CycloneDX', specVersion => '1.6',
    metadata => { component => { 'bom-ref' => 'perlonjava' } },
    components => [
        {
            group => 'org.perlonjava.fork', name => 'joni-fork',
            version => '2.2.7', 'bom-ref' => $joni_ref,
            properties => [
                { name => 'perlonjava:source-commit', value => $commit },
            ],
        },
        {
            group => 'org.jruby.jcodings', name => 'jcodings',
            version => '1.0.64', 'bom-ref' => $jcodings_ref,
        },
    ],
    dependencies => [
        { ref => 'perlonjava', dependsOn => [$joni_ref] },
        { ref => $joni_ref, dependsOn => [$jcodings_ref] },
    ],
});

for my $case (
    ['newline', "control/new\nline.class"],
    ['carriage return', "control/carriage\rreturn.class"],
    ['tab', "control/tab\tname.class"],
    ['DEL', "control/del\x7fname.class"],
) {
    my ($jar, $sbom) = fixture("control-$case->[0]", $case->[1]);
    rejected($jar, $sbom, qr/unsafe member name/, "$case->[0] in member name");
}

for my $case (
    ['FIFO', 0010000],
    ['socket', 0140000],
    ['block device', 0060000],
    ['character device', 0020000],
) {
    my $name = 'special/' . lc($case->[0]) . '.class';
    $name =~ s/ /-/g;
    my ($jar, $sbom) = fixture("type-$case->[0]", $name,
        type => $case->[1]);
    rejected($jar, $sbom, qr/unsafe Unix member type/,
        "$case->[0] member metadata");
}

{
    my ($jar, $sbom) = fixture('case-policy-duplicate', [
        'case/Case.class', 'case/case.class',
    ]);
    rejected($jar, $sbom, qr/canonical duplicate member/,
        'case-only duplicate member names');
}

{
    my ($jar, $sbom) = fixture('selected-directory-alias',
        'org/perlonjava/core/Configuration.class/');
    rejected($jar, $sbom, qr/canonical duplicate member/,
        'trailing-slash alias of selected member');
}

{
    my ($jar, $sbom) = fixture('regular-named-directory', 'typed/regular/',
        type => 0100000);
    rejected($jar, $sbom, qr/directory name and Unix type disagree/,
        'regular-file type with directory name');
}

{
    my ($jar, $sbom) = fixture('directory-named-file', 'typed/directory.class',
        type => 0040000);
    rejected($jar, $sbom, qr/directory name and Unix type disagree/,
        'directory type without directory name');
}

{
    my ($jar, $sbom) = fixture('valid-regular', 'typed/regular.class',
        type => 0100000);
    accepted($jar, $sbom, 'regular-file Unix type is accepted');
}

{
    my ($jar, $sbom) = fixture('valid-directory', 'typed/directory/',
        type => 0040000);
    accepted($jar, $sbom, 'directory Unix type and trailing slash agree');
}

done_testing;

sub fixture {
    my ($label, $extra_name, %option) = @_;
    $label =~ s/[^A-Za-z0-9_.-]+/-/g;
    my $jar = File::Spec->catfile($tmp, "$label.jar");
    my $sbom = File::Spec->catfile($tmp, "$label.sbom.json");
    write_raw($sbom, $sbom_bytes);
    my @extra_names = ref($extra_name) eq 'ARRAY' ? @$extra_name : ($extra_name);
    my @members = (
        ['org/perlonjava/core/Configuration.class', "commit $commit\n"],
        ['META-INF/sbom/sbom.json', $sbom_bytes],
        ['org/perlonjava/internal/joni/Regex.class', "joni\n"],
        ['org/perlonjava/internal/jcodings/Encoding.class', "jcodings\n"],
        (map { [$_, "fixture\n"] } @extra_names),
    );
    my $zip = IO::Compress::Zip->new($jar, Name => $members[0][0])
        or die "Cannot create fixture JAR: $ZipError";
    print {$zip} $members[0][1];
    for my $member (@members[1 .. $#members]) {
        $zip->newStream(Name => $member->[0])
            or die "Cannot add fixture member: $ZipError";
        print {$zip} $member->[1];
    }
    $zip->close or die "Cannot close fixture JAR: $ZipError";
    set_central_unix_type($jar, $extra_name, $option{type})
        if defined $option{type};
    return ($jar, $sbom);
}

sub set_central_unix_type {
    my ($path, $wanted, $type) = @_;
    my $bytes = read_raw($path);
    my $offset = 0;
    while (($offset = index($bytes, "PK\001\002", $offset)) >= 0) {
        die 'Truncated central directory fixture' if $offset + 46 > length($bytes);
        my ($name_length, $extra_length, $comment_length) =
            unpack('vvv', substr($bytes, $offset + 28, 6));
        my $name = substr($bytes, $offset + 46, $name_length);
        if ($name eq $wanted) {
            my $permissions = $type == 0040000 ? 0755 : 0644;
            my $dos_attributes = $type == 0040000 ? 0x10 : 0;
            substr($bytes, $offset + 4, 2, pack('v', (3 << 8) | 20));
            substr($bytes, $offset + 38, 4,
                pack('V', (($type | $permissions) << 16) | $dos_attributes));
            write_raw($path, $bytes);
            return;
        }
        $offset += 46 + $name_length + $extra_length + $comment_length;
    }
    die "Cannot find central member $wanted";
}

sub accepted {
    my ($jar, $sbom, $name) = @_;
    my $ok = eval { inspect_jar_sbom($jar, $sbom, $commit); 1 };
    ok($ok, $name) or diag($@);
}

sub rejected {
    my ($jar, $sbom, $expected, $name) = @_;
    my $ok = eval { inspect_jar_sbom($jar, $sbom, $commit); 1 };
    ok(!$ok, "$name is rejected");
    like($@, $expected, "$name has a fail-closed diagnostic");
}

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
