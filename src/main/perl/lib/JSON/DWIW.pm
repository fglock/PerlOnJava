package JSON::DWIW;

use strict;
use warnings;
use Exporter qw(import);
use JSON::DWIW::Boolean;
use PerlOnJava::JSONDWIWBackend ();

our $VERSION = '0.47';
our @EXPORT_OK = qw(to_json from_json deserialize_json);
our %EXPORT_TAGS = (all => \@EXPORT_OK);
our ($LastError, $LastErrorData, $Last_Stats);

sub new {
    my ($class, $options) = @_;
    bless(ref($options) eq 'HASH' ? { %$options } : {}, ref($class) || $class);
}

sub _call {
    my ($proto, @args) = @_;
    my ($self, $value);
    if (eval { UNIVERSAL::isa($proto, __PACKAGE__) }) {
        $self = ref($proto) ? $proto : $proto->new;
        $value = shift @args;
    } else {
        $self = __PACKAGE__->new;
        $value = $proto;
    }
    my $options = shift @args;
    $options = { %$self, %$options } if ref($options) eq 'HASH';
    $options ||= $self;
    return ($self, $value, $options);
}

sub to_json {
    my $proto = shift;
    my ($self, $data, $options) = _call($proto, @_);
    my ($error, $error_data, %stats);
    my $text = PerlOnJava::JSONDWIWBackend::_xs_to_json(
        $options, $data, \$error, \$error_data, \%stats);
    $LastError = $self->{last_error} = $error;
    $LastErrorData = $self->{last_error_data} = $error_data;
    $Last_Stats = $self->{last_stats} = \%stats;
    die $error if defined($error) && $options->{use_exceptions};
    return wantarray ? ($text, $error) : $text;
}
*toJson = \&to_json; *toJSON = \&to_json; *objToJson = \&to_json;

sub serialize { shift if @_ > 1 && $_[0] eq __PACKAGE__; __PACKAGE__->to_json(@_) }

sub deserialize {
    shift if @_ && !ref($_[0]) && $_[0] eq __PACKAGE__;
    return PerlOnJava::JSONDWIWBackend::deserialize(@_);
}
sub deserialize_json { deserialize(@_) }

sub from_json {
    my $proto = shift;
    my ($self, $text, $options) = _call($proto, @_);
    my $data = PerlOnJava::JSONDWIWBackend::deserialize($text, $options);
    $self->{last_error} = $LastError;
    $self->{last_error_data} = $LastErrorData;
    $self->{last_stats} = $Last_Stats;
    die $LastError if defined($LastError) && $options->{use_exceptions};
    return wantarray ? ($data, $LastError) : $data;
}
*fromJson = \&from_json; *fromJSON = \&from_json; *jsonToObj = \&from_json;

sub from_json_file {
    my $proto = shift;
    my ($self, $file, $options) = _call($proto, @_);
    my $data = PerlOnJava::JSONDWIWBackend::deserialize_file($file, $options);
    $self->{last_error} = $LastError;
    $self->{last_error_data} = $LastErrorData;
    $self->{last_stats} = $Last_Stats;
    die $LastError if defined($LastError) && $options->{use_exceptions};
    return wantarray ? ($data, $LastError) : $data;
}

sub to_json_file {
    my ($proto, $data, $file, $options) = @_;
    my ($text, $error) = $proto->to_json($data, $options);
    return wantarray ? (undef, $error) : undef if defined $error;
    my $fh;
    if (!open $fh, '>', $file) {
        my $message = "JSON::DWIW v$VERSION - couldn't open output file $file";
        return wantarray ? (undef, $message) : undef;
    }
    print {$fh} $text;
    close $fh;
    return wantarray ? (1, undef) : 1;
}

sub get_error_string { ref($_[0]) ? $_[0]{last_error} : $LastError }
*get_err_str = \&get_error_string; *errstr = \&get_error_string;
sub get_error_data { ref($_[0]) ? $_[0]{last_error_data} : $LastErrorData }
*get_error = \&get_error_data; *error = \&get_error_data;
sub get_stats { ref($_[0]) ? $_[0]{last_stats} : $Last_Stats }
*stats = \&get_stats;

sub true { JSON::DWIW::Boolean->true }
sub false { JSON::DWIW::Boolean->false }

for my $name (qw(
    do_dummy_parse has_deserialize deserialize_file have_big_int have_big_float
    size_of_uv peek_scalar has_high_bit_bytes is_valid_utf8 upgrade_to_utf8
    flagged_as_utf8 flag_as_utf8 unflag_as_utf8 code_point_to_utf8_str
    code_point_to_hex_bytes bytes_to_code_points _has_mmap _parse_mmap_file
    _check_scalar skip_deserialize_file get_ref_addr get_ref_type
)) {
    no strict 'refs';
    *{$name} = sub { goto &{"PerlOnJava::JSONDWIWBackend::$name"} };
}

1;

__END__

=head1 NAME

JSON::DWIW - relaxed JSON conversion for PerlOnJava

=head1 DESCRIPTION

This is a compatibility port of JSON::DWIW 0.47. It keeps the public Perl API
and implements the native conversion primitives with PerlOnJava's bundled
JSON::PP engine.

=cut
