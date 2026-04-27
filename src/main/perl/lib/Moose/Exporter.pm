package Moose::Exporter;

# PerlOnJava skeleton stub for Moose::Exporter.
#
# Upstream Moose::Exporter is a meta-exporter that lets a module declare
# Moose-style sugar (with, has, before/after/around, etc.) and have it
# installed into consumers via a proper import. PerlOnJava's
# Moose-as-Moo shim doesn't host that machinery; this stub provides
# `setup_import_methods` as a coarse pass-through that just installs
# `import` and `unimport` on the target package, calling `Moose->import`
# / `Moose->unimport` from the caller's perspective.
#
# It's enough for many MooseX::* extensions to compile. They won't
# install custom sugar correctly — that requires the real Moose::Exporter
# — but they'll at least load.
#
# See dev/modules/moose_support.md.

use strict;
use warnings;

our $VERSION = '2.4000';

use Carp ();

sub setup_import_methods {
    my (%opts) = @_;
    my $also = $opts{also};

    # Determine which package to install import/unimport into.
    my $into = $opts{into} || (caller)[0];

    # @also is the list of other Moose-style packages whose sugar we
    # forward — for the shim, we treat them all as "use Moose".
    my @also =
          !defined $also ? ()
        : ref $also eq 'ARRAY' ? @$also
        : ($also);

    my $with_meta   = $opts{with_meta}   || [];
    my $as_is       = $opts{as_is}       || [];
    my $with_caller = $opts{with_caller} || [];

    my @to_export = (@$with_meta, @$as_is, @$with_caller);

    no strict 'refs';
    no warnings 'redefine';

    *{"${into}::import"} = sub {
        my ($class, @args) = @_;
        my $caller = caller;
        return if $caller eq 'main';

        # Forward to Moose's import in the consumer's package.
        require Moose;
        Moose->import({ into => $caller });

        # Apply also-packages similarly.
        for my $pkg (@also) {
            eval "package $caller; require $pkg; $pkg\->import; 1";
        }

        # Install named exports on the consumer.
        for my $name (@to_export) {
            my $code = do { no strict 'refs'; \&{"${into}::${name}"} };
            next unless $code;
            *{"${caller}::${name}"} = $code;
        }
    };

    *{"${into}::unimport"} = sub {
        my $caller = caller;
        require Moose;
        Moose->unimport({ into => $caller });
    };

    *{"${into}::init_meta"} = sub { return; };

    return;
}

sub build_import_methods    { goto &setup_import_methods }
sub setup_unimport_methods  { return; }
sub setup_init_meta         { return; }

1;

__END__

=head1 NAME

Moose::Exporter - PerlOnJava skeleton stub.

=head1 DESCRIPTION

Provides a coarse C<setup_import_methods> that installs an C<import> /
C<unimport> on the calling package which, in turn, calls
C<< Moose->import / Moose->unimport >> on the consumer. This is enough
for many simple "extend Moose" sugar packages to compile, but does not
implement the full Moose::Exporter contract (custom sugar installation,
re-export tracking, init_meta chains).

=cut
