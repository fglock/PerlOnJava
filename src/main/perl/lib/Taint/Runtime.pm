package Taint::Runtime;

use strict;
use warnings;
use Exporter;
use XSLoader;

our @ISA = qw(Exporter);
our $VERSION = '0.03';
our $TAINT;
our %EXPORT_TAGS = (
    all => [qw(
        taint_start taint_stop taint_enabled tainted is_tainted
        taint untaint taint_env taint_deeply $TAINT
    )],
);
our @EXPORT_OK = @{ $EXPORT_TAGS{all} };
our @EXPORT = qw(taint_start taint_stop);

XSLoader::load('Taint::Runtime', $VERSION);

tie $TAINT, __PACKAGE__;

sub TIESCALAR { bless [], __PACKAGE__ }
sub FETCH { _taint_enabled() ? 1 : 0 }
sub STORE {
    my ($self, $value) = @_;
    $value = 0 if !$value || $value eq 'disable';
    $value ? _taint_start() : _taint_stop();
}

sub import {
    my $change;
    for my $i (reverse 1 .. $#_) {
        next if $_[$i] !~ /^(dis|en)able$/;
        my $value = $1 eq 'dis' ? 0 : 1;
        splice @_, $i, 1, ();
        die 'Cannot both enable and disable $TAINT during import'
            if defined $change && $change != $value;
        $change = $value;
        $TAINT = $value;
    }
    __PACKAGE__->export_to_level(1, @_);
}

sub taint_start { _taint_start() }
sub taint_stop { _taint_stop() }
sub taint_enabled { _taint_enabled() }
sub tainted { _tainted() }

sub is_tainted {
    return if !defined $_[0];
    !eval { eval '#' . substr($_[0], 0, 0); 1 };
}

sub taint {
    my $string = shift;
    my $ref = ref($string) ? $string : \$string;
    $$ref = '' if !defined $$ref;
    $$ref .= tainted();
    return ref($string) ? 1 : $string;
}

sub untaint {
    my $string = shift;
    my $ref = ref($string) ? $string : \$string;
    if (!defined $$ref) {
        $$ref = undef;
    } else {
        $$ref = ($$ref =~ /(.*)/s) ? $1 : do {
            require Carp;
            Carp::confess("Couldn't find data to untaint");
        };
    }
    return ref($string) ? 1 : $string;
}

sub taint_env { taint_deeply(\%ENV) }

sub taint_deeply {
    my ($ref, $seen) = @_;
    return if !defined $ref;
    if (!ref $ref) {
        taint \$_[0];
        return;
    } elsif (UNIVERSAL::isa($ref, 'SCALAR')) {
        taint $ref;
        return;
    }
    $seen ||= {};
    return if $seen->{$ref};
    $seen->{$ref} = 1;
    if (UNIVERSAL::isa($ref, 'ARRAY')) {
        taint_deeply($_, $seen) for @$ref;
    } elsif (UNIVERSAL::isa($ref, 'HASH')) {
        while (my ($key, $value) = each %$ref) {
            taint_deeply($key);
            taint_deeply($value, $seen);
            $ref->{$key} = $value;
        }
    }
}

1;

__END__

=head1 NAME

Taint::Runtime - runtime control of PerlOnJava taint checking

=head1 DESCRIPTION

This is the PerlOnJava port of Taint::Runtime 0.03.  It retains Paul
Seamons' Perl interface and replaces the four XS primitives with Java methods
backed by PerlOnJava's native scalar taint metadata and per-runtime taint mode.

=head1 COPYRIGHT AND LICENSE

The original module is copyright 2004-2005 Paul Seamons.  This port is free
software under the same terms as Perl itself.

=cut
