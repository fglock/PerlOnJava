package PerlOnJava::CpanJarSbom;

use strict;
use warnings;

use Digest::SHA qw(sha256_hex);
use Exporter qw(import);
use IO::Uncompress::Unzip qw($UnzipError);
use JSON::PP;

our @EXPORT_OK = qw(decode_strict_json inspect_jar_sbom);

use constant {
    MAX_ARCHIVE_MEMBERS => 200_000,
    MAX_CENTRAL_DIRECTORY_BYTES => 128 * 1024 * 1024,
    MAX_MEMBER_BYTES => 32 * 1024 * 1024,
    MAX_TOTAL_SELECTED_BYTES => 128 * 1024 * 1024,
    MAX_JSON_DEPTH => 64,
};

sub inspect_jar_sbom {
    my ($jar, $sbom, $expected_commit) = @_;
    die "Expected source commit is not a full Git SHA\n"
        unless defined($expected_commit) && $expected_commit =~ /\A[0-9a-f]{40}\z/;

    my $external = read_bounded($sbom, MAX_MEMBER_BYTES, 'selected SBOM');
    reject_duplicate_json_keys($external, 'selected SBOM');
    my $document = eval { JSON::PP->new->utf8->decode($external) };
    die "Selected SBOM is invalid JSON\n" if $@ || ref($document) ne 'HASH';

    my $central_names = inspect_central_directory($jar);
    my $zip = IO::Uncompress::Unzip->new($jar, Strict => 1, Transparent => 0)
        or die "Selected JAR is not a valid ZIP archive: $UnzipError\n";
    my (%seen, %canonical_seen, %selected, $members, $selected_bytes);
    while (1) {
        die "Selected JAR exceeds the member-count bound\n"
            if ++$members > MAX_ARCHIVE_MEMBERS;
        my $header = $zip->getHeaderInfo;
        my $name = $header->{Name} // '';
        validate_member_name($name);
        die "Selected JAR local and central member names differ\n"
            unless defined($central_names->[$members - 1])
                && $name eq $central_names->[$members - 1];
        die "Selected JAR contains a duplicate member: $name\n" if $seen{$name}++;
        my $identity = canonical_member_identity($name);
        die "Selected JAR contains a canonical duplicate member: $name\n"
            if $canonical_seen{$identity}++;
        my $wanted = $name eq 'org/perlonjava/core/Configuration.class'
            || $name eq 'META-INF/sbom/sbom.json'
            || $name =~ m{\Aorg/perlonjava/internal/(?:joni|jcodings)/.+\.class\z};
        if ($wanted) {
            my $bytes = '';
            while (1) {
                my $read = $zip->read(my $chunk, 8192);
                die "Cannot read selected JAR member $name: $UnzipError\n"
                    unless defined $read;
                last unless $read;
                $bytes .= $chunk;
                die "Selected JAR member exceeds its byte bound: $name\n"
                    if length($bytes) > MAX_MEMBER_BYTES;
                $selected_bytes += $read;
                die "Selected JAR selected-member set exceeds its byte bound\n"
                    if $selected_bytes > MAX_TOTAL_SELECTED_BYTES;
            }
            $selected{$name} = $bytes;
        }
        my $next = $zip->nextStream;
        last unless $next;
    }
    $zip->close or die "Cannot close selected JAR: $UnzipError\n";
    die "Selected JAR local and central member counts differ\n"
        unless $members == @$central_names;

    my $configuration = $selected{'org/perlonjava/core/Configuration.class'};
    die "Selected JAR lacks Configuration.class\n" unless defined $configuration;
    my %commit = map { lc($_) => 1 }
        ($configuration =~ /(?<![0-9a-f])([0-9a-f]{40})(?![0-9a-f])/ig);
    die "Selected JAR Configuration.class does not contain exactly one embedded commit\n"
        unless keys(%commit) == 1;
    my ($actual_commit) = keys %commit;
    die "Selected JAR embedded commit differs from selected source commit\n"
        unless $actual_commit eq $expected_commit;

    my $embedded = $selected{'META-INF/sbom/sbom.json'};
    die "Selected JAR lacks its embedded merged SBOM\n" unless defined $embedded;
    die "Selected JAR embedded SBOM differs from selected external SBOM\n"
        unless $embedded eq $external;
    die "Selected JAR lacks relocated Joni payload bytes\n"
        unless grep { m{\Aorg/perlonjava/internal/joni/.+\.class\z} } keys %selected;
    die "Selected JAR lacks relocated JCodings payload bytes\n"
        unless grep { m{\Aorg/perlonjava/internal/jcodings/.+\.class\z} } keys %selected;

    validate_sbom_relation($document, $expected_commit);
    return {
        jar_embedded_commit => $actual_commit,
        embedded_sbom_sha256 => sha256_hex($embedded),
        sbom_relation_sha256 => sha256_hex(JSON::PP->new->canonical->encode({
            root => 'perlonjava',
            joni => 'pkg:generic/perlonjava/joni-fork@2.2.7',
            jcodings => 'pkg:maven/org.jruby.jcodings/jcodings@1.0.64?type=jar',
            source_commit => $expected_commit,
        })),
    };
}

sub decode_strict_json {
    my ($bytes, $label) = @_;
    reject_duplicate_json_keys($bytes, $label);
    my $document = eval { JSON::PP->new->utf8->decode($bytes) };
    die "$label is invalid JSON\n" if $@ || ref($document) ne 'HASH';
    return $document;
}

sub validate_sbom_relation {
    my ($doc, $commit) = @_;
    die "Selected SBOM is not a CycloneDX 1.6 document\n"
        unless ($doc->{bomFormat} // '') eq 'CycloneDX'
            && ($doc->{specVersion} // '') eq '1.6'
            && ref($doc->{metadata}) eq 'HASH'
            && ref($doc->{metadata}{component}) eq 'HASH'
            && ($doc->{metadata}{component}{'bom-ref'} // '') eq 'perlonjava'
            && ref($doc->{components}) eq 'ARRAY'
            && ref($doc->{dependencies}) eq 'ARRAY';
    my $joni_ref = 'pkg:generic/perlonjava/joni-fork@2.2.7';
    my $jcodings_ref = 'pkg:maven/org.jruby.jcodings/jcodings@1.0.64?type=jar';
    assert_unique_components($doc->{components});
    my $joni = assert_component($doc->{components}, 'org.perlonjava.fork',
        'joni-fork', '2.2.7', $joni_ref);
    my $jcodings = assert_component($doc->{components}, 'org.jruby.jcodings',
        'jcodings', '1.0.64', $jcodings_ref);
    assert_optional_mit_license($joni, 'joni-fork');
    assert_optional_mit_license($jcodings, 'jcodings');
    assert_properties($joni, {
        'perlonjava:vendored' => 'true',
        'perlonjava:modified' => 'true',
        'perlonjava:vendored-source-path' => 'third_party/joni',
        'perlonjava:source-commit' => $commit,
        'perlonjava:upstream-maven-coordinate' => 'org.jruby.joni:joni:2.2.7',
        'perlonjava:upstream-tag' => 'joni-2.2.7',
        'perlonjava:upstream-commit' => '57fd57b4f977813a7b4b35e0179943b1f06f51d7',
    });
    for my $edge (['perlonjava', $joni_ref], [$joni_ref, $jcodings_ref]) {
        my @from = grep { ref($_) eq 'HASH' && ($_->{ref} // '') eq $edge->[0] }
            @{$doc->{dependencies}};
        die "Selected SBOM dependency source is missing or duplicated: $edge->[0]\n"
            unless @from == 1 && ref($from[0]{dependsOn}) eq 'ARRAY';
        my @matching = grep { defined($_) && !ref($_) && $_ eq $edge->[1] }
            @{$from[0]{dependsOn}};
        die "Selected SBOM dependency relation is missing: $edge->[0] -> $edge->[1]\n"
            unless @matching;
        die "Selected SBOM dependency relation is duplicated: $edge->[0] -> $edge->[1]\n"
            unless @matching == 1;
    }
}

sub inspect_central_directory {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read selected JAR: $!\n";
    my $size = -s $fh;
    die "Selected JAR is too short to be a ZIP archive\n" unless $size >= 22;
    my $tail_length = $size < 65_557 ? $size : 65_557;
    seek($fh, $size - $tail_length, 0)
        or die "Cannot seek selected JAR: $!\n";
    my $tail = read_exact($fh, $tail_length, 'selected JAR trailer');
    my $eocd;
    my $search = length($tail) - 22;
    while ($search >= 0) {
        my $candidate = rindex($tail, "PK\005\006", $search);
        last if $candidate < 0;
        if ($candidate + 22 <= length($tail)) {
            my $comment_length = unpack('v', substr($tail, $candidate + 20, 2));
            if ($candidate + 22 + $comment_length == length($tail)) {
                $eocd = $candidate;
                last;
            }
        }
        $search = $candidate - 1;
    }
    die "Selected JAR has no canonical end-of-central-directory record\n"
        unless defined $eocd;
    my ($disk, $central_disk, $disk_members, $members, $central_size,
        $central_offset) = unpack('vvvvVV', substr($tail, $eocd + 4, 16));
    die "Selected JAR uses unsupported split or ZIP64 archive metadata\n"
        if $disk || $central_disk || $disk_members != $members
            || $members == 0xffff || $central_size == 0xffffffff
            || $central_offset == 0xffffffff;
    die "Selected JAR exceeds the member-count bound\n"
        if $members > MAX_ARCHIVE_MEMBERS;
    die "Selected JAR central directory exceeds its byte bound\n"
        if $central_size > MAX_CENTRAL_DIRECTORY_BYTES;
    my $absolute_eocd = $size - $tail_length + $eocd;
    die "Selected JAR central directory is not confined before its trailer\n"
        unless $central_offset + $central_size == $absolute_eocd;
    seek($fh, $central_offset, 0)
        or die "Cannot seek selected JAR central directory: $!\n";
    my $central = read_exact($fh, $central_size, 'selected JAR central directory');
    close $fh or die "Cannot close selected JAR metadata: $!\n";

    my $offset = 0;
    my (@names, %seen, %canonical_seen);
    while ($offset < length($central)) {
        die "Selected JAR has a truncated central-directory member\n"
            if $offset + 46 > length($central)
                || substr($central, $offset, 4) ne "PK\001\002";
        my ($name_length, $extra_length, $comment_length) =
            unpack('vvv', substr($central, $offset + 28, 6));
        my $record_length = 46 + $name_length + $extra_length + $comment_length;
        die "Selected JAR has a truncated central-directory member\n"
            if $offset + $record_length > length($central);
        my $name = substr($central, $offset + 46, $name_length);
        validate_member_name($name);
        die "Selected JAR contains a duplicate member: $name\n" if $seen{$name}++;
        my $identity = canonical_member_identity($name);
        die "Selected JAR contains a canonical duplicate member: $name\n"
            if $canonical_seen{$identity}++;
        my $external = unpack('V', substr($central, $offset + 38, 4));
        my $unix_type = ($external >> 16) & 0170000;
        die "Selected JAR member has symlink-like metadata: $name\n"
            if $unix_type == 0120000;
        die "Selected JAR member has an unsafe Unix member type: $name\n"
            if $unix_type != 0 && $unix_type != 0100000
                && $unix_type != 0040000;
        my $directory_name = $name =~ m{/\z} ? 1 : 0;
        die "Selected JAR member directory name and Unix type disagree: $name\n"
            if $unix_type != 0
                && (($unix_type == 0040000 ? 1 : 0) != $directory_name);
        push @names, $name;
        $offset += $record_length;
    }
    die "Selected JAR central-directory member count differs from its trailer\n"
        unless @names == $members;
    return \@names;
}

sub validate_member_name {
    my ($name) = @_;
    my $path = $name;
    $path =~ s{/\z}{};
    my @parts = split m{/}, $path, -1;
    die "Selected JAR contains an unsafe member name\n"
        if !length($name) || !length($path) || $name =~ /[\x00-\x1f\x7f]/
            || $name =~ /\\/
            || $name =~ m{\A/} || $name =~ /\A[A-Za-z]:/
            || grep { $_ eq '' || $_ eq '.' || $_ eq '..' } @parts;
}

sub canonical_member_identity {
    my ($name) = @_;
    $name =~ s{/\z}{};
    $name =~ tr/A-Z/a-z/;
    return $name;
}

sub read_exact {
    my ($fh, $length, $label) = @_;
    my $bytes = '';
    while (length($bytes) < $length) {
        my $read = read($fh, my $chunk, $length - length($bytes));
        die "Cannot read $label: $!\n" unless defined $read;
        die "$label is truncated\n" unless $read;
        $bytes .= $chunk;
    }
    return $bytes;
}

sub assert_unique_components {
    my ($components) = @_;
    my (%refs, %purls);
    for my $component (@$components) {
        die "Selected SBOM component is not an object\n"
            unless ref($component) eq 'HASH';
        for my $field (['bom-ref', \%refs], ['purl', \%purls]) {
            my ($name, $seen) = @$field;
            next unless defined($component->{$name}) && length($component->{$name});
            die "Selected SBOM has duplicate $name: $component->{$name}\n"
                if $seen->{$component->{$name}}++;
        }
    }
}

sub assert_component {
    my ($components, $group, $name, $version, $ref) = @_;
    my @matching = grep { ($_->{group} // '') eq $group
        && ($_->{name} // '') eq $name } @$components;
    my $canonical_name = $name eq 'joni-fork' ? 'Joni fork' : 'JCodings';
    die "Selected SBOM does not contain a canonical $canonical_name component\n"
        unless @matching;
    die "Selected SBOM has duplicate $name components\n" unless @matching == 1;
    my $component = $matching[0];
    die "Selected SBOM has wrong $name type\n"
        if exists($component->{type})
            && (!defined($component->{type}) || ref($component->{type})
                || $component->{type} ne 'library');
    die "Selected SBOM has wrong $name version\n"
        unless ($component->{version} // '') eq $version;
    die "Selected SBOM has wrong $name bom-ref\n"
        unless ($component->{'bom-ref'} // '') eq $ref;
    die "Selected SBOM has wrong $name purl\n"
        if exists($component->{purl})
            && (!defined($component->{purl}) || ref($component->{purl})
                || $component->{purl} ne $ref);
    return $component;
}

sub assert_optional_mit_license {
    my ($component, $name) = @_;
    return unless exists $component->{licenses};
    die "Selected SBOM has wrong or missing $name license\n"
        unless ref($component->{licenses}) eq 'ARRAY';
    my @licenses = map { ref($_) eq 'HASH' && ref($_->{license}) eq 'HASH'
        && defined($_->{license}{id}) && !ref($_->{license}{id})
        ? $_->{license}{id} : '' } @{$component->{licenses}};
    die "Selected SBOM has wrong or missing $name license\n"
        unless @licenses == 1 && $licenses[0] eq 'MIT';
}

sub assert_properties {
    my ($component, $required) = @_;
    die "Selected SBOM Joni fork has no provenance properties\n"
        unless ref($component->{properties}) eq 'ARRAY';
    my %values;
    for my $property (@{$component->{properties}}) {
        next unless ref($property) eq 'HASH';
        my $name = $property->{name} // '';
        push @{$values{$name}}, $property->{value} // '' if exists $required->{$name};
    }
    for my $name (sort keys %$required) {
        my $found = $values{$name} // [];
        my $mandatory = $name eq 'perlonjava:source-commit';
        die "Selected SBOM Joni fork has missing or duplicate $name property\n"
            if ($mandatory && @$found != 1) || (!$mandatory && @$found > 1);
        next unless @$found;
        die "Selected SBOM Joni fork has wrong $name property\n"
            unless $found->[0] eq $required->{$name};
    }
}

sub read_bounded {
    my ($path, $maximum, $label) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $label: $!\n";
    my $bytes = '';
    while (1) {
        my $read = read($fh, my $chunk, 8192);
        die "Cannot read $label: $!\n" unless defined $read;
        last unless $read;
        $bytes .= $chunk;
        die "$label exceeds its byte bound\n" if length($bytes) > $maximum;
    }
    close $fh or die "Cannot close $label: $!\n";
    return $bytes;
}

sub reject_duplicate_json_keys {
    my ($bytes, $label) = @_;
    pos($bytes) = 0;
    parse_json_value(\$bytes, 0, $label);
    skip_space(\$bytes);
    die "$label has trailing JSON data\n" unless pos($bytes) == length($bytes);
}

sub parse_json_value {
    my ($source, $depth, $label) = @_;
    die "$label exceeds the maximum JSON nesting depth\n" if $depth > MAX_JSON_DEPTH;
    skip_space($source);
    my $next = substr($$source, pos($$source), 1);
    if ($next eq '{') {
        ++pos($$source); skip_space($source); my %seen;
        return ++pos($$source) if substr($$source, pos($$source), 1) eq '}';
        while (1) {
            my $key = parse_string($source, $label);
            die "$label contains duplicate JSON key: $key\n" if $seen{$key}++;
            skip_space($source);
            die "$label has malformed JSON object syntax\n"
                unless substr($$source, pos($$source)++, 1) eq ':';
            parse_json_value($source, $depth + 1, $label); skip_space($source);
            my $separator = substr($$source, pos($$source)++, 1);
            last if $separator eq '}';
            die "$label has malformed JSON object syntax\n" unless $separator eq ',';
            skip_space($source);
        }
        return;
    }
    if ($next eq '[') {
        ++pos($$source); skip_space($source);
        return ++pos($$source) if substr($$source, pos($$source), 1) eq ']';
        while (1) {
            parse_json_value($source, $depth + 1, $label); skip_space($source);
            my $separator = substr($$source, pos($$source)++, 1);
            last if $separator eq ']';
            die "$label has malformed JSON array syntax\n" unless $separator eq ',';
        }
        return;
    }
    return parse_string($source, $label) if $next eq '"';
    $$source =~ /\G(?:true|false|null|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)/gc
        or die "$label has malformed JSON value\n";
}

sub parse_string {
    my ($source, $label) = @_;
    my $start = pos($$source);
    die "$label has malformed JSON string\n" unless substr($$source, $start, 1) eq '"';
    ++pos($$source);
    while (pos($$source) < length($$source)) {
        my $character = substr($$source, pos($$source)++, 1);
        if ($character eq '"') {
            my $token = substr($$source, $start, pos($$source) - $start);
            my $decoded = eval { JSON::PP->new->decode($token) };
            die "$label has malformed JSON string\n" if $@ || ref($decoded);
            return $decoded;
        }
        die "$label has a control character in a JSON string\n" if ord($character) < 0x20;
        if ($character eq '\\') {
            my $escape = substr($$source, pos($$source)++, 1);
            die "$label has malformed JSON escape\n" unless $escape =~ /["\\\/bfnrtu]/;
            if ($escape eq 'u') {
                my $hex = substr($$source, pos($$source), 4);
                die "$label has malformed JSON Unicode escape\n"
                    unless $hex =~ /\A[0-9a-fA-F]{4}\z/;
                pos($$source) += 4;
            }
        }
    }
    die "$label has unterminated JSON string\n";
}

sub skip_space { ${$_[0]} =~ /\G[\x20\x09\x0a\x0d]*/gc }

1;
