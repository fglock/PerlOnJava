package PerlOnJava::JSONDWIWBackend;

use strict;
use warnings;
use JSON::PP ();
use Encode ();
use Scalar::Util qw(blessed reftype refaddr);

sub _json {
    my ($options) = @_;
    $options ||= {};
    my $json = JSON::PP->new
        ->allow_nonref(1)->allow_blessed(1)->allow_bignum(1)
        ->relaxed(1)->allow_singlequote(1)->allow_barekey(1);
    $json->pretty(1)       if $options->{pretty};
    $json->canonical(1)    if $options->{sort_keys};
    $json->ascii(1)        if $options->{ascii} || $options->{escape_multi_byte};
    return $json;
}

sub _normalize_for_encode {
    my ($value, $seen) = @_;
    return $value if !ref $value;
    $seen ||= {};
    my $addr = refaddr($value);
    return undef if $addr && $seen->{$addr}++;

    if (blessed($value) && $value->isa('JSON::DWIW::Boolean')) {
        return $value ? JSON::PP::true() : JSON::PP::false();
    }
    my $type = reftype($value) || '';
    if ($type eq 'HASH') {
        return { map { $_ => _normalize_for_encode($value->{$_}, $seen) } keys %$value };
    }
    if ($type eq 'ARRAY') {
        return [ map { _normalize_for_encode($_, $seen) } @$value ];
    }
    if ($type eq 'SCALAR' || $type eq 'REF') {
        return _normalize_for_encode($$value, $seen);
    }
    return "$value";
}

sub _convert_bools {
    my ($value) = @_;
    if (blessed($value) && $value->isa('JSON::PP::Boolean')) {
        require JSON::DWIW::Boolean;
        return $value ? JSON::DWIW::Boolean->true : JSON::DWIW::Boolean->false;
    }
    if (ref($value) eq 'HASH') {
        $value->{$_} = _convert_bools($value->{$_}) for keys %$value;
    } elsif (ref($value) eq 'ARRAY') {
        $_ = _convert_bools($_) for @$value;
    }
    return $value;
}

sub _stats {
    my ($value, $stats, $depth, $seen) = @_;
    $depth ||= 0;
    $seen ||= {};
    $stats->{max_depth} = $depth if !defined($stats->{max_depth}) || $depth > $stats->{max_depth};
    if (ref $value) {
        my $addr = refaddr($value);
        return if $addr && $seen->{$addr}++;
    }
    if (!defined $value) {
        $stats->{nulls}++;
    } elsif (blessed($value) && ($value->isa('JSON::PP::Boolean') || $value->isa('JSON::DWIW::Boolean'))) {
        $stats->{bools}++;
    } elsif (ref($value) eq 'HASH') {
        $stats->{hashes}++;
        $stats->{strings} += scalar keys %$value;
        _stats($value->{$_}, $stats, $depth + 1, $seen) for keys %$value;
    } elsif (ref($value) eq 'ARRAY') {
        $stats->{arrays}++;
        _stats($_, $stats, $depth + 1, $seen) for @$value;
    } elsif ($value =~ /^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$/) {
        $stats->{numbers}++;
    } else {
        $stats->{strings}++;
    }
}

sub _set_stats {
    my ($value, $text, $target) = @_;
    my %stats = map { $_ => 0 } qw(hashes arrays strings numbers bools nulls);
    _stats($value, \%stats, 0);
    $stats{bytes} = length Encode::encode_utf8($text // '');
    $stats{chars} = length($text // '');
    $stats{lines} = 1 + (($text // '') =~ tr/\n//);
    %$target = %stats if ref($target) eq 'HASH';
    $JSON::DWIW::Last_Stats = \%stats;
    return \%stats;
}

sub _relax_input {
    my ($text) = @_;
    $text =~ s/^\x{feff}//;
    $text =~ s/\\x([0-9a-fA-F]{2})/\\u00$1/g;
    $text =~ s/\\v/\\u000b/g;
    $text =~ s/([{,]\s*)(\$?[A-Za-z_][A-Za-z0-9_]*)(\s*:)/$1"$2"$3/g;
    1 while $text =~ s/,\s*,/,/g;
    $text =~ s/,\s*([}\]])/$1/g;
    return $text;
}

sub deserialize {
    shift if @_ && !ref($_[0]) && $_[0] eq 'JSON::DWIW';
    my ($text, $options) = @_;
    $options ||= {};
    local $@;
    my $data = eval { _json($options)->decode(_relax_input($text)) };
    if ($@) {
        my $error = "JSON::DWIW v0.47 - $@";
        $JSON::DWIW::LastError = $error;
        $JSON::DWIW::LastErrorData = { line => 1, byte => 0, char => 0 };
        die $error if $options->{use_exceptions};
        return undef;
    }
    if ($options->{convert_bool}) {
        $data = _convert_bools($data);
    }
    $JSON::DWIW::LastError = undef;
    $JSON::DWIW::LastErrorData = undef;
    _set_stats($data, $text, {});
    return $data;
}

sub deserialize_file {
    shift if @_ && !ref($_[0]) && $_[0] eq 'JSON::DWIW';
    my ($file, $options) = @_;
    my $fh;
    if (!open $fh, '<', $file) {
        $JSON::DWIW::LastError = "JSON::DWIW v0.47 - couldn't open input file $file";
        return undef;
    }
    local $/;
    my $text = <$fh>;
    close $fh;
    return deserialize($text, $options);
}

sub _xs_to_json {
    my ($self, $data, $error_ref, $error_data_ref, $stats) = @_;
    my $options = ref($self) eq 'HASH' ? $self : {};
    local $@;
    my $normalized = _normalize_for_encode($data);
    my $text = eval { _json($options)->encode($normalized) };
    if ($@) {
        $$error_ref = "JSON::DWIW v0.47 - $@" if ref $error_ref;
        $$error_data_ref = {} if ref $error_data_ref;
        return undef;
    }
    if ($options->{bare_keys}) {
        $text =~ s/"([A-Za-z_][A-Za-z0-9_]*)"\s*:/$1:/g;
    }
    $$error_ref = undef if ref $error_ref;
    $$error_data_ref = undef if ref $error_data_ref;
    _set_stats($data, $text, $stats);
    return $text;
}

sub has_deserialize { 1 }
sub do_dummy_parse { 1 }
sub have_big_int { eval { require Math::BigInt; 1 } ? 1 : 0 }
sub have_big_float { eval { require Math::BigFloat; 1 } ? 1 : 0 }
sub size_of_uv { 8 }
sub skip_deserialize_file { 0 }
sub _has_mmap { 0 }
sub _parse_mmap_file { deserialize_file(@_) }
sub _check_scalar { defined $_[-1] ? 1 : 0 }
sub peek_scalar { "$_[-1]" }
sub has_high_bit_bytes { $_[-1] =~ /[^\x00-\x7f]/ ? 1 : 0 }
sub flagged_as_utf8 { utf8::is_utf8($_[-1]) ? 1 : 0 }
sub flag_as_utf8 { utf8::decode($_[-1]); return $_[-1] }
sub unflag_as_utf8 { utf8::encode($_[-1]); return $_[-1] }
sub upgrade_to_utf8 { utf8::decode($_[-1]); return $_[-1] }
sub is_valid_utf8 { Encode::decode('UTF-8', Encode::encode('UTF-8', $_[-1]), Encode::FB_CROAK()) ? 1 : 0 }
sub code_point_to_utf8_str { chr($_[-1]) }
sub code_point_to_hex_bytes { join '', map { sprintf '\\x%02x', $_ } unpack('C*', code_point_to_utf8_str($_[-1])) }
sub bytes_to_code_points { [ unpack('U*', Encode::decode_utf8($_[-1])) ] }
sub get_ref_addr { refaddr($_[-1]) || 0 }
sub get_ref_type { reftype($_[-1]) || '' }

1;
