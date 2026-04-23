package JSON;

use strict;
use warnings;
use Carp ();
use Exporter ();
use Symbol;

our $VERSION = '4.11';
our @ISA = qw(Exporter);
our @EXPORT = qw(from_json to_json jsonToObj objToJson encode_json decode_json);

# PerlOnJava acts as the XS backend.  Declare ourselves so CPAN JSON.pm-
# style introspection (JSON->backend, $JSON::Backend, is_xs/is_pp) works
# without the real CPAN dispatcher being loaded.
our $Backend          = 'JSON';
our $BackendModule    = 'JSON';
our $BackendModuleXS  = 'JSON';
our $BackendModulePP;

our $DEBUG = 0;
$DEBUG = $ENV{PERL_JSON_DEBUG} if exists $ENV{PERL_JSON_DEBUG};

XSLoader::load( 'Json' );

# NOTE: encode/decode/true/false/null/is_bool are defined in
#       src/main/java/org/perlonjava/runtime/perlmodule/Json.java

my %RequiredVersion = (
    'JSON::PP' => '2.27203',
    'JSON::XS' => '2.34',
);

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

my @PPOnlyMethods = qw/
    indent_length sort_by
    allow_singlequote allow_bignum loose allow_barekey escape_slash as_nonblessed
/;

# CPAN JSON.pm supports several special import tags.  We accept them so
# modules that write `use JSON -support_by_pp;` load without failing, even
# though we don't actually implement the pp-only feature set.
sub import {
    my $pkg = shift;
    my @to_export;
    my $no_export;

    for my $tag (@_) {
        if ($tag eq '-support_by_pp') {
            # no-op: PerlOnJava JSON already treats pp-only methods as settable
            next;
        }
        elsif ($tag eq '-no_export') {
            $no_export++;
            next;
        }
        elsif ($tag eq '-convert_blessed_universally') {
            # no-op: handled by convert_blessed option on the object
            next;
        }
        push @to_export, $tag;
    }
    return if $no_export;
    __PACKAGE__->export_to_level(1, $pkg, @to_export);
}

sub new {
    my ($class) = @_;
    return bless {}, $class;
}

# Backend introspection — called as both class and instance method.
sub backend            { $Backend }
sub is_xs              { 1 }
sub is_pp              { 0 }
sub require_xs_version { $RequiredVersion{'JSON::XS'} }
sub pureperl_only_methods { @PPOnlyMethods }

# Provide a null() routine (CPAN JSON exports it indirectly via some paths).
sub null { undef }


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

# Obsolete aliases (CPAN JSON keeps them with a warn).
sub jsonToObj {
    my $alt = 'from_json';
    if (defined $_[0] and UNIVERSAL::isa($_[0], 'JSON')) {
        shift @_;
        $alt = 'decode';
    }
    Carp::carp "'jsonToObj' will be obsoleted. Please use '$alt' instead.";
    return JSON::from_json(@_);
}

sub objToJson {
    my $alt = 'to_json';
    if (defined $_[0] and UNIVERSAL::isa($_[0], 'JSON')) {
        shift @_;
        $alt = 'encode';
    }
    Carp::carp "'objToJson' will be obsoleted. Please use '$alt' instead.";
    return JSON::to_json(@_);
}

sub boolean {
    # might be called as method or as function, so pop() to get the last arg instead of shift() to get the first
    pop() ? true() : false()
}

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

# Options we don't fully honor but accept without error so modules that
# chain ->max_depth(...)->max_size(...)->sort_by(...) continue to work.
for my $name (qw(
    max_depth max_size indent_length
    filter_json_object filter_json_single_key_object
    sort_by
)) {
    no strict 'refs';
    my $sym = Symbol::qualify_to_ref($name, __PACKAGE__);
    *$sym = sub {
        my $self = shift;
        $self->{$name} = @_ ? $_[0] : 1;
        $self;
    };
    my $gsym = Symbol::qualify_to_ref("get_$name", __PACKAGE__);
    *$gsym = sub { $_[0]->{$name} };
}

# Stubs for incremental parsing.  The CPAN modules support stream parsing
# via a buffer that accumulates partial input; PerlOnJava's JSON doesn't
# yet implement this, but returning an empty result (rather than dying
# with "Can't locate object method") lets most sanity tests proceed.
sub incr_text {
    my $self = shift;
    if (@_) {
        $self->{_incr_buf} = $_[0];
        return $self;
    }
    return $self->{_incr_buf};
}

sub incr_parse {
    my ($self, $data) = @_;
    $self->{_incr_buf} = '' unless defined $self->{_incr_buf};
    $self->{_incr_buf} .= $data if defined $data;
    # Best-effort: try to decode whatever we have; on failure leave buffer intact.
    return unless length $self->{_incr_buf};
    my $res = eval { $self->decode($self->{_incr_buf}) };
    if ($@) { return; }  # need more data
    $self->{_incr_buf} = '';
    return $res;
}

sub incr_skip {
    my $self = shift;
    $self->{_incr_buf} = '';
    return;
}

sub incr_reset {
    my $self = shift;
    $self->{_incr_buf} = '';
    return $self;
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

Author and Copyright messages from the original JSON.pm:

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
