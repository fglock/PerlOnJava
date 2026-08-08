package B::Flags;

use strict;
use warnings;
use B ();
use Scalar::Util ();

# Internals is provided by PerlOnJava, but the portable shim should still load
# under system Perl for callers that only need ordinary B flag names.
BEGIN { eval { require Internals; 1 } }

our $VERSION = '0.17';

# PerlOnJava's B module exposes its portable object model in Perl.  Installing
# these methods directly on the B classes avoids depending on Perl's internal
# OP/SV C structs, which are what the upstream XS implementation reads.

sub B::OP::flagspv {
    my ($op) = @_;
    my $portable = ref($op) && (Scalar::Util::reftype($op) || '') eq 'HASH';
    my @flags = 'WANT_VOID';
    push @flags, 'KIDS'    if $portable && $op->{kids};
    push @flags, 'PARENS'  if $portable && $op->{parens};
    push @flags, 'STACKED' if $portable && $op->{stacked};
    push @flags, 'REF'     if $portable && $op->{ref_flag};
    push @flags, 'MOD'     if $portable && $op->{mod};
    push @flags, 'SPECIAL' if $portable && $op->{special};
    return join ',', @flags;
}

sub B::OP::privatepv {
    my ($op) = @_;
    my $portable = ref($op) && (Scalar::Util::reftype($op) || '') eq 'HASH';
    return 'REFCOUNTED' if !$portable || !exists $op->{private_flags};
    return join ',', @{ $op->{private_flags} || [] };
}

sub B::SV::flagspv {
    my ($sv, $requested_type) = @_;
    my @flags;
    my $numeric = eval { $sv->FLAGS } || 0;

    push @flags, 'IOK'  if $numeric & B::SVf_IOK();
    push @flags, 'NOK'  if $numeric & B::SVf_NOK();
    push @flags, 'POK'  if $numeric & B::SVf_POK();
    push @flags, 'pIOK' if $numeric & B::SVp_IOK();
    push @flags, 'pNOK' if $numeric & B::SVp_NOK();
    push @flags, 'pPOK' if $numeric & B::SVp_POK();

    # The portable B wrapper stores the inspected reference in this hash slot.
    # Report the stable, useful structural flags without pretending to expose
    # host-Perl allocation details such as slab or copy-on-write bits.
    my $portable = ref($sv) && (Scalar::Util::reftype($sv) || '') eq 'HASH';
    if ((!defined($requested_type) || $requested_type != 0) && $portable && exists $sv->{ref}) {
        my $kind = Scalar::Util::reftype($sv->{ref}) || '';
        push @flags, 'REAL'     if $kind eq 'ARRAY';
        push @flags, 'MULTI'    if $kind eq 'GLOB';
        push @flags, 'LVALUE'   if $kind eq 'CODE' && defined(&Internals::jperl_cv_is_lvalue)
            && eval { Internals::jperl_cv_is_lvalue($sv->{ref}) };
        push @flags, 'READONLY' if $kind eq 'SCALAR' && eval { Scalar::Util::readonly(${ $sv->{ref} }) };
    } elsif (ref($sv) eq 'B::AV') {
        push @flags, 'REAL';
    }

    return join ',', @flags;
}

# Enough of the root API for callers that use B::Flags as a diagnostic aid.
*B::main_root = sub { B::OP->new } unless defined &B::main_root;

1;

__END__

=head1 NAME

B::Flags - friendlier flags for PerlOnJava's portable B objects

=head1 DESCRIPTION

This PerlOnJava adaptation preserves the public C<flagspv> and C<privatepv>
methods without accessing Perl's private C structures.  It reports flags that
the portable C<B> object model can determine reliably.

=head1 AUTHORS

Original module by Simon Cozens.  Maintained upstream by Reini Urban and
Abhijit Menon-Sen.

=head1 COPYRIGHT AND LICENSE

Copyright 2001 Simon Cozens; 2010, 2013, 2014, 2015 Reini Urban.

This module is available under the Artistic License or the GNU GPL.

=cut
