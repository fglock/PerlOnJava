package Cpanel::JSON::XS::Type;

=pod

=head1 NAME

Cpanel::JSON::XS::Type - Type support for JSON encode

=head1 DESCRIPTION

See the upstream Cpanel-JSON-XS distribution on CPAN for full documentation.

On PerlOnJava, type I<constants> and specification helpers in this file work as
on CPAN, but L<Cpanel::JSON::XS/encode_json> and L<Cpanel::JSON::XS/decode_json>
do not accept type specifications until a Java XS implementation exists; the
bundled shim delegates encoding to L<JSON::PP>.

=head1 AUTHOR

Pali E<lt>pali@cpan.orgE<gt>

=head1 COPYRIGHT & LICENSE

Copyright (c) 2017, GoodData Corporation. All rights reserved.

This module is available under the same licences as perl, the Artistic
license and the GPL.

=cut

use strict;
use warnings;

BEGIN {
    if ( eval { require Scalar::Util } ) {
        Scalar::Util->import('weaken');
    }
    else {
        *weaken = sub ($) { die 'Scalar::Util is required for weaken' };
    }
}

# Values match XS.xs in Cpanel-JSON-XS.  Defined here in Perl because the
# Cpanel::JSON::XS shim has no Java XS bootstrap to install these constants.
use constant JSON_TYPE_SCALAR => 0x0000;
use constant JSON_TYPE_BOOL   => 0x0001;
use constant JSON_TYPE_INT    => 0x0002;
use constant JSON_TYPE_FLOAT  => 0x0003;
use constant JSON_TYPE_STRING => 0x0004;

use constant JSON_TYPE_CAN_BE_NULL => 0x0100;
use constant JSON_TYPE_NULL        => 0x0100;

use constant JSON_TYPE_BOOL_OR_NULL   => ( 0x0001 | 0x0100 );
use constant JSON_TYPE_INT_OR_NULL    => ( 0x0002 | 0x0100 );
use constant JSON_TYPE_FLOAT_OR_NULL  => ( 0x0003 | 0x0100 );
use constant JSON_TYPE_STRING_OR_NULL => ( 0x0004 | 0x0100 );

use constant JSON_TYPE_ARRAYOF_CLASS => 'Cpanel::JSON::XS::Type::ArrayOf';
use constant JSON_TYPE_HASHOF_CLASS  => 'Cpanel::JSON::XS::Type::HashOf';
use constant JSON_TYPE_ANYOF_CLASS   => 'Cpanel::JSON::XS::Type::AnyOf';

use Exporter;
our @ISA = qw(Exporter);
our @EXPORT = our @EXPORT_OK = qw(
  json_type_arrayof
  json_type_hashof
  json_type_anyof
  json_type_null_or_anyof
  json_type_weaken
  JSON_TYPE_NULL
  JSON_TYPE_BOOL
  JSON_TYPE_INT
  JSON_TYPE_FLOAT
  JSON_TYPE_STRING
  JSON_TYPE_BOOL_OR_NULL
  JSON_TYPE_INT_OR_NULL
  JSON_TYPE_FLOAT_OR_NULL
  JSON_TYPE_STRING_OR_NULL
  JSON_TYPE_ARRAYOF_CLASS
  JSON_TYPE_HASHOF_CLASS
  JSON_TYPE_ANYOF_CLASS
);

use constant JSON_TYPE_WEAKEN_CLASS => 'Cpanel::JSON::XS::Type::Weaken';

sub json_type_anyof {
    my ( $scalar, $array, $hash );
    my ( $scalar_weaken, $array_weaken, $hash_weaken );
    foreach (@_) {
        my $type = $_;
        my $ref  = ref($_);
        my $weaken;
        if ( $ref eq JSON_TYPE_WEAKEN_CLASS ) {
            $type = ${$type};
            $ref  = ref($type);
            $weaken = 1;
        }
        if ( $ref eq '' ) {
            die 'Only one scalar type can be specified in anyof' if defined $scalar;
            $scalar = $type;
            $scalar_weaken = $weaken;
        }
        elsif ( $ref eq 'ARRAY' or $ref eq JSON_TYPE_ARRAYOF_CLASS ) {
            die 'Only one array type can be specified in anyof' if defined $array;
            $array = $type;
            $array_weaken = $weaken;
        }
        elsif ( $ref eq 'HASH' or $ref eq JSON_TYPE_HASHOF_CLASS ) {
            die 'Only one hash type can be specified in anyof' if defined $hash;
            $hash = $type;
            $hash_weaken = $weaken;
        }
        else {
            die 'Only scalar, array or hash can be specified in anyof';
        }
    }
    my $type = [ $scalar, $array, $hash ];
    weaken $type->[0] if $scalar_weaken;
    weaken $type->[1] if $array_weaken;
    weaken $type->[2] if $hash_weaken;
    return bless $type, JSON_TYPE_ANYOF_CLASS;
}

sub json_type_null_or_anyof {
    foreach (@_) {
        die 'Scalar cannot be specified in null_or_anyof' if ref($_) eq '';
    }
    return json_type_anyof( JSON_TYPE_CAN_BE_NULL, @_ );
}

sub json_type_arrayof {
    die 'Exactly one type must be specified in arrayof' if scalar @_ != 1;
    my $type = $_[0];
    if ( ref($type) eq JSON_TYPE_WEAKEN_CLASS ) {
        $type = ${$type};
        weaken $type;
    }
    return bless \$type, JSON_TYPE_ARRAYOF_CLASS;
}

sub json_type_hashof {
    die 'Exactly one type must be specified in hashof' if scalar @_ != 1;
    my $type = $_[0];
    if ( ref($type) eq JSON_TYPE_WEAKEN_CLASS ) {
        $type = ${$type};
        weaken $type;
    }
    return bless \$type, JSON_TYPE_HASHOF_CLASS;
}

sub json_type_weaken {
    die 'Exactly one type must be specified in weaken' if scalar @_ != 1;
    die 'Scalar cannot be specfied in weaken' if ref( $_[0] ) eq '';
    return bless \( my $type = $_[0] ), JSON_TYPE_WEAKEN_CLASS;
}

1;
