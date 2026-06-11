# This is NOT a fork of upstream Cpanel::JSON::XS.
#
# PerlOnJava maps XS modules to Java via XSLoader (see bundled modules such as
# Digest::MD5, DBI, etc.).  There is no Java backend for Cpanel::JSON::XS yet, so
# this file provides the package by inheriting from the bundled JSON::PP stack
# (the same backend used by bundled L<JSON>) — see L<JSON> in this tree.
#
# When a full CPAN Cpanel/JSON/XS.pm is earlier in @INC (e.g. from ./jcpan),
# it calls XSLoader::load; XSLoader then evals this file from jar:PERL5LIB to
# wire JSON::PP as the implementation parent.
#
# Features that depend on the real C XS extension are not implemented here:
# type-aware encode_json/decode_json extra arguments, require_types, binary
# mode, etc.  Those croak with a clear message if used.

package Cpanel::JSON::XS;

use strict;
use warnings;

our $VERSION    = '4.40';
our $XS_VERSION = $VERSION;

require JSON::PP;
require Exporter;
require Carp;
use Scalar::Util qw(blessed);

our @ISA    = qw(JSON::PP Exporter);
our @EXPORT = qw(encode_json decode_json to_json from_json);

sub new {
    my ($class, @args) = @_;
    Carp::croak('Odd number of arguments for Cpanel::JSON::XS->new')
        if @args % 2;

    my $self = $class->SUPER::new();

    # JSON::PP enables allow_nonref by default for JSON.pm compatibility.
    # Cpanel::JSON::XS follows JSON::XS and requires callers to opt in.
    $self->allow_nonref(0);

    while (@args) {
        my ($name, $value) = splice @args, 0, 2;
        Carp::croak("Unsupported Cpanel::JSON::XS option '$name'")
            unless $self->can($name);
        $self->$name($value);
    }

    return $self;
}

sub encode {
    my ($self, $value) = @_;

    if (!$self->get_utf8) {
        $value = _upgrade_byte_strings_as_latin1($value);
    }

    return $self->SUPER::encode($value);
}

sub _upgrade_byte_strings_as_latin1 {
    my ($value) = @_;
    return $value unless defined $value;

    if (!ref $value) {
        my $copy = $value;
        utf8::upgrade($copy)
            if !utf8::is_utf8($copy) && $copy =~ /[\x80-\xff]/;
        return $copy;
    }

    return $value if blessed($value);

    if (ref($value) eq 'ARRAY') {
        return [ map { _upgrade_byte_strings_as_latin1($_) } @$value ];
    }

    if (ref($value) eq 'HASH') {
        my %copy;
        for my $key (keys %$value) {
            my $copy_key = $key;
            utf8::upgrade($copy_key)
                if !utf8::is_utf8($copy_key) && $copy_key =~ /[\x80-\xff]/;
            $copy{$copy_key} = _upgrade_byte_strings_as_latin1($value->{$key});
        }
        return \%copy;
    }

    return $value;
}

sub encode_json {
    my @args = @_;
    if ( @args && blessed( $args[0] ) && $args[0]->isa(__PACKAGE__) ) {
        shift @args;
    }
    elsif ( @args && !ref( $args[0] ) && $args[0] eq __PACKAGE__ ) {
        shift @args;
    }
    if ( @args > 1 ) {
        Carp::croak(
'Cpanel::JSON::XS type-aware encode_json (second argument) is not implemented in the PerlOnJava JSON::PP shim'
        );
    }
    @_ = @args;
    goto &JSON::PP::encode_json;
}

sub decode_json {
    if ( @_ > 3 ) {
        Carp::croak('Too many arguments for Cpanel::JSON::XS::decode_json');
    }
    my ( $text, $allow_nonref, $rtype ) = @_;
    if ( defined $rtype ) {
        Carp::croak(
'Cpanel::JSON::XS decode_json third argument (type output) is not implemented in the PerlOnJava JSON::PP shim'
        );
    }
    if ($allow_nonref) {
        return ( __PACKAGE__->new->utf8->allow_nonref(1) )->decode($text);
    }
    @_ = ($text);
    goto &JSON::PP::decode_json;
}

sub to_json ($@) {
    if ( $] >= 5.008 ) {
        Carp::croak(
            "Cpanel::JSON::XS::to_json has been renamed to encode_json,"
              . " either downgrade to pre-2.0 versions of Cpanel::JSON::XS or"
              . " rename the call" );
    }
    return encode_json( $_[0] );
}

sub from_json ($@) {
    if ( $] >= 5.008 ) {
        Carp::croak(
            "Cpanel::JSON::XS::from_json has been renamed to decode_json,"
              . " either downgrade to pre-2.0 versions of Cpanel::JSON::XS or"
              . " rename the call" );
    }
    return decode_json( $_[0] );
}

*true    = \&JSON::PP::true;
*false   = \&JSON::PP::false;
*is_bool = \&JSON::PP::is_bool;

sub stringify_infnan { return $_[0] }
sub escape_slash     { return $_[0] }
sub allow_dupkeys    { return $_[0] }

1;

__END__

=head1 NAME

Cpanel::JSON::XS - JSON::XS-compatible API via bundled L<JSON::PP> (PerlOnJava port)

=head1 DESCRIPTION

This is a PerlOnJava portability shim used until a Java XS backend exists.
Encoding and decoding are delegated to the same L<JSON::PP> implementation used
by bundled L<JSON>; see that module for option parity and error semantics.

=head1 AUTHOR

Original Cpanel::JSON::XS by Reini Urban and Marc Lehmann.  PerlOnJava shim
inherits behaviour from bundled L<JSON::PP>.

=head1 COPYRIGHT AND LICENSE

Same as Perl itself.  Refer to the upstream Cpanel-JSON-XS and JSON-PP
distributions on CPAN.

=cut
