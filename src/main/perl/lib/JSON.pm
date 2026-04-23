package JSON;

# PerlOnJava's bundled JSON backend.
#
# CPAN `JSON` is a pure-Perl dispatcher that loads `JSON::XS` or
# `JSON::PP`.  In PerlOnJava we use `JSON::PP` as the real backend —
# it is a complete pure-Perl implementation that handles every
# option, edge case and error message the CPAN test suite checks
# for.  A previous iteration of this shim delegated to a partial
# Java implementation in `Json.java`; that implementation did not
# honour most of the JSON::XS options and was replaced by the
# JSON::PP inheritance below.  `Json.java` is kept in the tree but
# no longer loaded by this module.
#
# We still provide the dispatcher's surface so code that writes
#   use JSON -support_by_pp;
#   my $v = JSON->backend->VERSION;
# continues to load cleanly.

use strict;
use warnings;
use Carp ();
use JSON::PP ();

our $VERSION = '4.11';

# Inherit all encode/decode/option methods from JSON::PP.  Using @ISA
# (rather than re-exporting every sub) means future JSON::PP updates
# are picked up automatically.
our @ISA = ('JSON::PP');

our @EXPORT = qw(from_json to_json jsonToObj objToJson encode_json decode_json);

# Backend introspection variables populated by the real CPAN JSON
# after it loads a backend.  We populate them up-front so tests that
# do `$JSON::Backend->VERSION` / `$JSON::BackendModulePP` etc. work.
our $Backend          = 'JSON::PP';
our $BackendModule    = 'JSON::PP';
our $BackendModulePP  = 'JSON::PP';
our $BackendModuleXS;   # left undef: no XS backend available

our $DEBUG = 0;
$DEBUG = $ENV{PERL_JSON_DEBUG} if exists $ENV{PERL_JSON_DEBUG};

my %RequiredVersion = (
    'JSON::PP' => '2.27203',
    'JSON::XS' => '2.34',
);

# PP-only methods, reported by pureperl_only_methods().
my @PPOnlyMethods = qw/
    indent_length sort_by
    allow_singlequote allow_bignum loose allow_barekey escape_slash as_nonblessed
/;

# CPAN JSON.pm supports several special import tags; accept them as
# no-ops so modules that use them at `use` time continue to load.
sub import {
    my $pkg = shift;
    my @to_export;
    my $no_export;

    for my $tag (@_) {
        if ($tag eq '-support_by_pp') {
            # already supported — JSON::PP is our backend
            next;
        }
        elsif ($tag eq '-no_export') {
            $no_export++;
            next;
        }
        elsif ($tag eq '-convert_blessed_universally') {
            # Install a default UNIVERSAL::TO_JSON like CPAN JSON does.
            my $org_encode = JSON::PP->can('encode');
            no warnings 'redefine';
            no strict 'refs';
            *{'JSON::PP::encode'} = sub {
                local *UNIVERSAL::TO_JSON = sub {
                    my $ref = ref $_[0];
                    return $ref eq 'HASH'  ? { %{$_[0]} }
                         : $ref eq 'ARRAY' ? [ @{$_[0]} ]
                         : undef;
                };
                $org_encode->(@_);
            };
            next;
        }
        push @to_export, $tag;
    }
    return if $no_export;
    __PACKAGE__->export_to_level(1, $pkg, @to_export);
}

# CPAN's `encode_json` / `decode_json` are package subs in JSON::PP,
# not imported-to-this-package.  Re-export them under the JSON
# package so `JSON::encode_json(...)` and a bare `encode_json(...)`
# after `use JSON` both work.
*encode_json = \&JSON::PP::encode_json;
*decode_json = \&JSON::PP::decode_json;
*is_bool     = \&JSON::PP::is_bool;
*true        = \&JSON::PP::true;
*false       = \&JSON::PP::false;
*null        = sub { undef };

# Backend introspection.  Works both as class and instance method.
sub backend               { $Backend }
sub is_xs                 { 0 }
sub is_pp                 { 1 }
sub require_xs_version    { $RequiredVersion{'JSON::XS'} }
sub pureperl_only_methods { @PPOnlyMethods }


# INTERFACES — thin wrappers matching CPAN JSON.pm exactly.

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
    # might be called as method or as function; use pop() to fetch the
    # intended boolean regardless.
    pop() ? true() : false()
}

# `property()` comes from JSON::PP via inheritance; the one chunk of
# logic CPAN JSON.pm has that JSON::PP lacks is treating `max_size 1`
# as 0.  Wrap it to preserve that.
sub property {
    my ($self, $name, $value) = @_;
    if (@_ == 2 && $name eq 'max_size') {
        my $v = $self->get_max_size;
        return $v == 1 ? 0 : $v;
    }
    return $self->SUPER::property(@_[1..$#_]);
}

1;

__END__

=head1 NAME

JSON - PerlOnJava bundled JSON backend (delegates to JSON::PP)

=head1 DESCRIPTION

This module exposes the CPAN C<JSON> dispatcher API but is backed
unconditionally by L<JSON::PP>, which is shipped inside the PerlOnJava
JAR.  The original hand-coded Java encoder in C<Json.java> has been
retired in favour of the complete pure-Perl implementation so that
every JSON::XS-style option, error message, and edge case matches the
CPAN test suite.

=head1 AUTHOR

The CPAN C<JSON> module is maintained by Kenichi Ishigaki, originally
authored by Makamaka Hannyaharamitu.  This PerlOnJava shim preserves
the author/licence of the original.

=head1 COPYRIGHT AND LICENSE

Copyright 2005-2013 by Makamaka Hannyaharamitu.

This library is free software; you can redistribute it and/or modify
it under the same terms as Perl itself.

=cut
