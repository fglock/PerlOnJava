package Params::Util::PP;

use strict;
use warnings;

our $VERSION = '1.102';

=pod

=head1 NAME

Params::Util::PP - PurePerl Params::Util routines

=cut

use Scalar::Util ();
use overload     ();

Scalar::Util->can("looks_like_number") and Scalar::Util->import("looks_like_number");
# Use a private pure-perl copy of looks_like_number if the version of
# Scalar::Util is old (for whatever reason).
Params::Util::PP->can("looks_like_number") or *looks_like_number = sub {
    local $_ = shift;

    # checks from perlfaq4
    return 0 if !defined($_);
    if (ref($_))
    {
        return overload::Overloaded($_) ? defined(0 + $_) : 0;
    }
    return 1 if (/^[+-]?[0-9]+$/);    # is a +/- integer
    ## no critic (RegularExpressions::ProhibitComplexRegexes)
    return 1 if (/^(?:[+-]?)(?=[0-9]|\.[0-9])[0-9]*(?:\.[0-9]*)?(?:[Ee](?:[+-]?[0-9]+))?$/);     # a C float
    return 1 if ($] >= 5.008 and /^(?:Inf(?:inity)?|NaN)$/i) or ($] >= 5.006001 and /^Inf$/i);

    0;
};

## no critic (Subroutines::ProhibitSubroutinePrototypes, Subroutines::RequireArgUnpacking)
## no critic (Subroutines::ProhibitUnusedPrivateSubroutines)

sub _XScompiled { return 0; }

sub _STRING ($)
{
    my $arg = $_[0];
    return (defined $arg and not ref $arg and length($arg)) ? $arg : undef;
}

sub _IDENTIFIER ($)
{
    my $arg = $_[0];
    return (defined $arg and not ref $arg and $arg =~ m/^[^\W\d]\w*\z/s) ? $arg : undef;
}

sub _CLASS ($)
{
    my $arg = $_[0];
    return (defined $arg and not ref $arg and $arg =~ m/^[^\W\d]\w*(?:::\w+)*\z/s) ? $arg : undef;
}

sub _CLASSISA ($$)
{
    return (defined $_[0] and not ref $_[0] and $_[0] =~ m/^[^\W\d]\w*(?:::\w+)*\z/s and $_[0]->isa($_[1])) ? $_[0] : undef;
}

sub _CLASSDOES ($$)
{
    return (defined $_[0] and not ref $_[0] and $_[0] =~ m/^[^\W\d]\w*(?:::\w+)*\z/s and $_[0]->DOES($_[1])) ? $_[0] : undef;
}

sub _SUBCLASS ($$)
{
    return (defined $_[0] and not ref $_[0] and $_[0] =~ m/^[^\W\d]\w*(?:::\w+)*\z/s and $_[0] ne $_[1] and $_[0]->isa($_[1]))
      ? $_[0]
      : undef;
}

sub _NUMBER ($)
{
    my $arg = $_[0];
    return (defined $arg and not ref $arg and looks_like_number($arg)) ? $arg : undef;
}

sub _POSINT ($)
{
    my $arg = $_[0];
    return (defined $arg and not ref $arg and $arg =~ m/^[1-9]\d*$/) ? $arg : undef;
}

sub _NONNEGINT ($)
{
    my $arg = $_[0];
    return (defined $arg and not ref $arg and $arg =~ m/^(?:0|[1-9]\d*)$/) ? $arg : undef;
}

sub _SCALAR ($)
{
    return (ref $_[0] eq 'SCALAR' and defined ${$_[0]} and ${$_[0]} ne '') ? $_[0] : undef;
}

sub _SCALAR0 ($)
{
    return ref $_[0] eq 'SCALAR' ? $_[0] : undef;
}

sub _ARRAY ($)
{
    return (ref $_[0] eq 'ARRAY' and @{$_[0]}) ? $_[0] : undef;
}

sub _ARRAY0 ($)
{
    return ref $_[0] eq 'ARRAY' ? $_[0] : undef;
}

sub _ARRAYLIKE
{
    return (
        defined $_[0] and ref $_[0] and ((Scalar::Util::reftype($_[0]) eq 'ARRAY')
            or overload::Method($_[0], '@{}'))
    ) ? $_[0] : undef;
}

sub _HASH ($)
{
    return (ref $_[0] eq 'HASH' and scalar %{$_[0]}) ? $_[0] : undef;
}

sub _HASH0 ($)
{
    return ref $_[0] eq 'HASH' ? $_[0] : undef;
}

sub _HASHLIKE
{
    return (
        defined $_[0] and ref $_[0] and ((Scalar::Util::reftype($_[0]) eq 'HASH')
            or overload::Method($_[0], '%{}'))
    ) ? $_[0] : undef;
}

sub _CODE ($)
{
    return ref $_[0] eq 'CODE' ? $_[0] : undef;
}

sub _CODELIKE($)
{
    return (
        (Scalar::Util::reftype($_[0]) || '') eq 'CODE'
          or Scalar::Util::blessed($_[0]) and overload::Method($_[0], '&{}')
    ) ? $_[0] : undef;
}

sub _INVOCANT($)
{
    return (
        defined $_[0]
          and (
            defined Scalar::Util::blessed($_[0])
            or
            # We used to check for stash definedness, but any class-like name is a
            # valid invocant for UNIVERSAL methods, so we stopped. -- rjbs, 2006-07-02
            _CLASS($_[0])
          )
    ) ? $_[0] : undef;
}

sub _INSTANCE ($$)
{
    return (Scalar::Util::blessed($_[0]) and $_[0]->isa($_[1])) ? $_[0] : undef;
}

sub _INSTANCEDOES ($$)
{
    return (Scalar::Util::blessed($_[0]) and $_[0]->DOES($_[1])) ? $_[0] : undef;
}

sub _REGEX ($)
{
    return (defined $_[0] and 'Regexp' eq ref($_[0])) ? $_[0] : undef;
}

sub _SET ($$)
{
    my $set_param = shift;
    _ARRAY($set_param) or return undef;
    foreach my $item (@$set_param)
    {
        _INSTANCE($item, $_[0]) or return undef;
    }
    return $set_param;
}

sub _SET0 ($$)
{
    my $set_param = shift;
    _ARRAY0($set_param) or return undef;
    foreach my $item (@$set_param)
    {
        _INSTANCE($item, $_[0]) or return undef;
    }
    return $set_param;
}

# We're doing this longhand for now. Once everything is perfect,
# we'll compress this into something that compiles more efficiently.
# Further, testing file handles is not something that is generally
# done millions of times, so doing it slowly is not a big speed hit.
sub _HANDLE
{
    my $it = shift;

    # It has to be defined, of course
    unless (defined $it)
    {
        return undef;
    }

    # Normal globs are considered to be file handles
    if (ref $it eq 'GLOB')
    {
        return $it;
    }

    # Check for a normal tied filehandle
    # Side Note: 5.5.4's tied() and can() doesn't like getting undef
    if (tied($it) and tied($it)->can('TIEHANDLE'))
    {
        return $it;
    }

    # There are no other non-object handles that we support
    unless (Scalar::Util::blessed($it))
    {
        return undef;
    }

    # Check for a common base classes for conventional IO::Handle object
    if ($it->isa('IO::Handle'))
    {
        return $it;
    }

    # Check for tied file handles using Tie::Handle
    if ($it->isa('Tie::Handle'))
    {
        return $it;
    }

    # IO::Scalar is not a proper seekable, but it is valid is a
    # regular file handle
    if ($it->isa('IO::Scalar'))
    {
        return $it;
    }

    # Yet another special case for IO::String, which refuses (for now
    # anyway) to become a subclass of IO::Handle.
    if ($it->isa('IO::String'))
    {
        return $it;
    }

    # This is not any sort of object we know about
    return undef;
}

sub _DRIVER ($$)
{
    ## no critic (BuiltinFunctions::ProhibitStringyEval)
    return (defined _CLASS($_[0]) and eval "require $_[0];" and not $@ and $_[0]->isa($_[1]) and $_[0] ne $_[1]) ? $_[0] : undef;
}

1;
