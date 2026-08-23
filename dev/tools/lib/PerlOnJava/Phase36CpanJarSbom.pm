package PerlOnJava::Phase36CpanJarSbom;

use strict;
use warnings;

use Digest::SHA qw(sha256_hex);
use Exporter qw(import);
use IO::Uncompress::Unzip qw($UnzipError);
use JSON::PP;

our @EXPORT_OK = qw(decode_strict_json inspect_jar_sbom);

use constant {
    MAX_ARCHIVE_MEMBERS => 200_000,
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

    my $zip = IO::Uncompress::Unzip->new($jar, Strict => 1, Transparent => 0)
        or die "Selected JAR is not a valid ZIP archive: $UnzipError\n";
    my (%seen, %selected, $members, $selected_bytes);
    while (1) {
        die "Selected JAR exceeds the member-count bound\n"
            if ++$members > MAX_ARCHIVE_MEMBERS;
        my $header = $zip->getHeaderInfo;
        my $name = $header->{Name} // '';
        die "Selected JAR contains an unsafe member name\n"
            if !length($name) || $name =~ /\0/ || $name =~ m{(?:\A|/)\.\.(?:/|\z)};
        die "Selected JAR contains a duplicate member: $name\n" if $seen{$name}++;
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
    my @joni = grep { ref($_) eq 'HASH'
        && ($_->{group} // '') eq 'org.perlonjava.fork'
        && ($_->{name} // '') eq 'joni-fork'
        && ($_->{'bom-ref'} // '') eq $joni_ref } @{$doc->{components}};
    my @jcodings = grep { ref($_) eq 'HASH'
        && ($_->{group} // '') eq 'org.jruby.jcodings'
        && ($_->{name} // '') eq 'jcodings'
        && ($_->{'bom-ref'} // '') eq $jcodings_ref } @{$doc->{components}};
    die "Selected SBOM does not contain exactly one canonical Joni fork component\n"
        unless @joni == 1;
    die "Selected SBOM does not contain exactly one canonical JCodings component\n"
        unless @jcodings == 1;
    my @commits = map { ref($_) eq 'HASH'
        && ($_->{name} // '') eq 'perlonjava:source-commit'
        ? ($_->{value} // '') : () } @{$joni[0]{properties} // []};
    die "Selected SBOM Joni fork commit does not match selected source\n"
        unless @commits == 1 && $commits[0] eq $commit;
    for my $edge (['perlonjava', $joni_ref], [$joni_ref, $jcodings_ref]) {
        my @from = grep { ref($_) eq 'HASH' && ($_->{ref} // '') eq $edge->[0] }
            @{$doc->{dependencies}};
        die "Selected SBOM dependency source is missing or duplicated: $edge->[0]\n"
            unless @from == 1 && ref($from[0]{dependsOn}) eq 'ARRAY';
        die "Selected SBOM dependency relation is missing: $edge->[0] -> $edge->[1]\n"
            unless grep { defined($_) && !ref($_) && $_ eq $edge->[1] }
                @{$from[0]{dependsOn}};
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
