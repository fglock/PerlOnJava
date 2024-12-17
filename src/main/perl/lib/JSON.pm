package JSON;

use Exporter "import";
use warnings;
use strict;
use Symbol;

# NOTE: The rest of the code is in file:
#       src/main/java/org/perlonjava/perlmodule/Json.java

our @EXPORT = ("encode_json", "decode_json", "to_json", "from_json");

my @PublicMethods = qw/
    ascii latin1 utf8 pretty indent space_before space_after relaxed canonical allow_nonref
    allow_blessed convert_blessed filter_json_object filter_json_single_key_object
    shrink max_depth max_size encode decode decode_prefix allow_unknown
    allow_tags
/;

my @Properties = qw/
    ascii latin1 utf8 indent space_before space_after relaxed canonical allow_nonref
    allow_blessed convert_blessed shrink max_depth max_size allow_unknown
    allow_tags
/;

sub new {
    my ($class) = @_;
    return bless {}, $class;
}

sub is_xs { 1 };
sub is_pp { 0 };


# INTERFACES

sub to_json ($@) {
    if (
        ref($_[0]) eq 'JSON'
            or (@_ > 2 and $_[0] eq 'JSON')
    ) {
        Carp::croak "to_json should not be called as a method.";
    }
    my $json = JSON->new;

    if (@_ == 2 and ref $_[1] eq 'HASH') {
        my $opt  = $_[1];
        for my $method (keys %$opt) {
            $json->$method( $opt->{$method} );
        }
    }

    $json->encode($_[0]);
}


sub from_json ($@) {
    if ( ref($_[0]) eq 'JSON' or $_[0] eq 'JSON' ) {
        Carp::croak "from_json should not be called as a method.";
    }
    my $json = JSON->new;

    if (@_ == 2 and ref $_[1] eq 'HASH') {
        my $opt  = $_[1];
        for my $method (keys %$opt) {
            $json->$method( $opt->{$method} );
        }
    }

    return $json->decode( $_[0] );
}

sub boolean {
    # might be called as method or as function, so pop() to get the last arg instead of shift() to get the first
    pop() ? true() : false()
}

sub require_xs_version {}

sub backend {}

sub property {
    my ($self, $name, $value) = @_;

    if (@_ == 1) {
        my %props;
        for $name (@Properties) {
            my $method = 'get_' . $name;
            if ($name eq 'max_size') {
                my $value = $self->$method();
                $props{$name} = $value == 1 ? 0 : $value;
                next;
            }
            $props{$name} = $self->$method();
        }
        return \%props;
    }
    elsif (@_ > 3) {
        Carp::croak('property() can take only the option within 2 arguments.');
    }
    elsif (@_ == 2) {
        if ( my $method = $self->can('get_' . $name) ) {
            if ($name eq 'max_size') {
                my $value = $self->$method();
                return $value == 1 ? 0 : $value;
            }
            $self->$method();
        }
    }
    else {
        $self->$name($value);
    }

}

BEGIN {
    my @xs_compati_bit_properties = qw(
        latin1 ascii utf8 indent canonical space_before space_after allow_nonref shrink
        allow_blessed convert_blessed relaxed allow_unknown
        allow_tags
    );
    my @pp_bit_properties = qw(
        allow_singlequote allow_bignum loose
        allow_barekey escape_slash as_nonblessed
    );
    for my $name (@xs_compati_bit_properties, @pp_bit_properties) {
        my $sym_ref = Symbol::qualify_to_ref($name, __PACKAGE__);
        *$sym_ref = sub {
            if ($_[1] // 1) {
                $_[0]->{$name} = 1;
            }
            else {
                $_[0]->{$name} = 0;
            }
            $_[0];
        };
        $sym_ref = Symbol::qualify_to_ref("get_$name", __PACKAGE__);
        *$sym_ref = sub {
            $_[0]->{$name} ? 1 : '';
        };
    }
}

# pretty printing

sub pretty {
    my ($self, $v) = @_;
    my $enable = defined $v ? $v : 1;

    if ($enable) { # indent_length(3) for JSON::XS compatibility
        $self->indent(1)->space_before(1)->space_after(1);
    }
    else {
        $self->indent(0)->space_before(0)->space_after(0);
    }

    $self;
}

# Functions

my $JSON; # cache

sub encode_json ($) { # encode
    ($JSON ||= __PACKAGE__->new->utf8)->encode(@_);
}


sub decode_json { # decode
    ($JSON ||= __PACKAGE__->new->utf8)->decode(@_);
}

1;

__END__

=head1 AUTHOR

Makamaka Hannyaharamitu, E<lt>makamaka[at]cpan.orgE<gt>

JSON::XS was written by  Marc Lehmann E<lt>schmorp[at]schmorp.deE<gt>

The release of this new version owes to the courtesy of Marc Lehmann.

=head1 CURRENT MAINTAINER

Kenichi Ishigaki, E<lt>ishigaki[at]cpan.orgE<gt>

=head1 COPYRIGHT AND LICENSE

Copyright 2005-2013 by Makamaka Hannyaharamitu

Most of the documentation is taken from JSON::XS by Marc Lehmann

This library is free software; you can redistribute it and/or modify
it under the same terms as Perl itself.

=cut
