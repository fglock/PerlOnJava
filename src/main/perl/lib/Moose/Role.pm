package Moose::Role;

# PerlOnJava Moose::Role shim. Delegates to Moo::Role; translates string
# isa => 'Type' on `has` declarations into coderef checks (same translation
# Moose.pm performs for classes).
#
# See dev/modules/moose_support.md.

use strict;
use warnings;

our $VERSION = '2.4000';

use Moo::Role ();
use Carp ();
use Scalar::Util ();
use Moose ();   # for _make_isa_check / _translate_has_args
use Class::MOP ();   # ensure Class::MOP::class_of is defined before
                     # Moo::Role's setup_role calls into it

sub import {
    my ($class, @args) = @_;
    my $target = caller;

    return if $target eq 'main';

    strict->import;
    warnings->import;

    my $err;
    {
        local $@;
        eval "package $target; use Moo::Role; 1" or $err = $@ || 'unknown error';
    }
    Carp::croak("Moose::Role shim: failed to load Moo::Role for $target: $err")
        if $err;

    # Wrap target's `has` to translate Moose-style options AND record
    # the attribute on the target's _FakeMeta.
    my $orig_has = do { no strict 'refs'; \&{"${target}::has"} };
    if ($orig_has) {
        no strict 'refs';
        no warnings 'redefine';
        *{"${target}::has"} = sub {
            my @orig_args = @_;
            my $rv = $orig_has->( Moose::_translate_has_args(@orig_args) );
            my $meta = Moose::_FakeMeta->_for($target);
            my $names = $orig_args[0];
            for my $n (ref $names eq 'ARRAY' ? @$names : ($names)) {
                next unless defined $n && !ref $n;
                my %opts = @orig_args[1..$#orig_args];
                $meta->add_attribute(name => $n, %opts);
            }
            return $rv;
        };
    }

    # meta() stub.
    no strict 'refs';
    unless (defined &{"${target}::meta"}) {
        *{"${target}::meta"} = sub { Moose::_FakeMeta->_for($target) };
    }
}

sub unimport {
    my $target = caller;
    no strict 'refs';
    for my $sym (qw(has with before after around requires meta excludes)) {
        delete ${"${target}::"}{$sym};
    }
}

1;

__END__

=head1 NAME

Moose::Role - PerlOnJava Moose::Role compatibility shim (delegates to Moo::Role)

=head1 SEE ALSO

L<Moose>, L<Moo::Role>, C<dev/modules/moose_support.md>

=cut
