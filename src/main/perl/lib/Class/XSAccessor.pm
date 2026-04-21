package Class::XSAccessor;
use 5.008;
use strict;
use warnings;
use Carp qw/croak/;
use Class::XSAccessor::Heavy;

our $VERSION = '1.19';

# PerlOnJava note: this is a pure-Perl re-implementation of
# Class::XSAccessor. The original module uses XS for speed; PerlOnJava
# has no XS loader, so we install equivalent closures directly. The
# public interface, option names, and semantics match upstream exactly
# — the only observable difference is speed.

sub _make_hash {
    my $ref = shift;

    if (ref($ref)) {
        if (ref($ref) eq 'ARRAY') {
            $ref = { map { $_ => $_ } @$ref };
        }
    } else {
        $ref = { $ref, $ref };
    }

    return $ref;
}

sub import {
    my $own_class = shift;
    my ($caller_pkg) = caller();

    # Support both { getters => ... } and plain getters => ...
    my %opts = ref($_[0]) eq 'HASH' ? %{$_[0]} : @_;

    $caller_pkg = $opts{class} if defined $opts{class};

    my $read_subs      = _make_hash($opts{getters}            || {});
    my $set_subs       = _make_hash($opts{setters}            || {});
    my $acc_subs       = _make_hash($opts{accessors}          || {});
    my $lvacc_subs     = _make_hash($opts{lvalue_accessors}   || {});
    my $pred_subs      = _make_hash($opts{predicates}         || {});
    my $ex_pred_subs   = _make_hash($opts{exists_predicates}  || {});
    my $def_pred_subs  = _make_hash($opts{defined_predicates} || {});
    my $test_subs      = _make_hash($opts{__tests__}          || {});
    my $construct_subs = $opts{constructors}
        || [defined($opts{constructor}) ? $opts{constructor} : ()];
    my $true_subs      = $opts{true}  || [];
    my $false_subs     = $opts{false} || [];

    foreach my $subtype (["getter",           $read_subs],
                         ["setter",           $set_subs],
                         ["accessor",         $acc_subs],
                         ["lvalue_accessor",  $lvacc_subs],
                         ["test",             $test_subs],
                         ["ex_predicate",     $ex_pred_subs],
                         ["def_predicate",    $def_pred_subs],
                         ["def_predicate",    $pred_subs])
    {
        my $subs = $subtype->[1];
        foreach my $subname (keys %$subs) {
            my $hashkey = $subs->{$subname};
            _generate_method($caller_pkg, $subname, $hashkey, \%opts, $subtype->[0]);
        }
    }

    foreach my $subtype (["constructor", $construct_subs],
                         ["true",        $true_subs],
                         ["false",       $false_subs])
    {
        foreach my $subname (@{$subtype->[1]}) {
            _generate_method($caller_pkg, $subname, "", \%opts, $subtype->[0]);
        }
    }
}

sub _generate_method {
    my ($caller_pkg, $subname, $hashkey, $opts, $type) = @_;

    croak("Cannot use undef as a hash key for generating an XS $type accessor. (Sub: $subname)")
        if not defined $hashkey;

    $subname = "${caller_pkg}::$subname" if $subname !~ /::/;

    Class::XSAccessor::Heavy::check_sub_existence($subname) if not $opts->{replace};

    my $code = _build_accessor($type, $hashkey, $opts);

    no strict 'refs';
    no warnings 'redefine';
    *{$subname} = $code;
}

# Returns a coderef implementing the requested accessor kind.
# Works on hash-based objects (see Class::XSAccessor::Array for array-based).
sub _build_accessor {
    my ($type, $hashkey, $opts) = @_;
    my $chained = $opts->{chained} ? 1 : 0;

    if ($type eq 'getter') {
        # Real XSAccessor getters croak on extra args ("Usage: ..."),
        # which Moo relies on to implement read-only ("ro") attributes.
        return sub {
            croak(sprintf('Usage: %s(self)', (caller(0))[3]))
                if @_ != 1;
            $_[0]->{$hashkey};
        };
    }
    elsif ($type eq 'setter') {
        return $chained
            ? sub { $_[0]->{$hashkey} = $_[1]; $_[0] }
            : sub { $_[0]->{$hashkey} = $_[1] };
    }
    elsif ($type eq 'accessor') {
        return $chained
            ? sub {
                if (@_ > 1) { $_[0]->{$hashkey} = $_[1]; return $_[0] }
                return $_[0]->{$hashkey};
              }
            : sub {
                $_[0]->{$hashkey} = $_[1] if @_ > 1;
                $_[0]->{$hashkey};
              };
    }
    elsif ($type eq 'lvalue_accessor') {
        # Perl lvalue sub — assigning to the call site modifies the
        # underlying hash slot. Works both as getter and as an lvalue
        # target: $obj->baz = 42;
        return eval 'sub : lvalue { $_[0]->{$hashkey} }';
    }
    elsif ($type eq 'def_predicate') {
        return sub { defined $_[0]->{$hashkey} };
    }
    elsif ($type eq 'ex_predicate') {
        return sub { exists $_[0]->{$hashkey} };
    }
    elsif ($type eq 'constructor') {
        # Matches upstream: bless { @args }, ref($class) || $class
        return sub {
            my $class = shift;
            return bless { @_ }, ref($class) || $class;
        };
    }
    elsif ($type eq 'true') {
        return sub () { 1 };
    }
    elsif ($type eq 'false') {
        return sub () { !1 };
    }
    elsif ($type eq 'test') {
        # __tests__ is used internally by the XS build for regression
        # checks; in PP we just return a truthy constant.
        return sub { $_[0]->{$hashkey} ? 1 : 0 };
    }
    else {
        croak("Unknown Class::XSAccessor sub type: $type");
    }
}

1;

__END__

=head1 NAME

Class::XSAccessor - Generate fast accessors (PerlOnJava pure-Perl port)

=head1 DESCRIPTION

PerlOnJava bundles a pure-Perl re-implementation of
L<Class::XSAccessor|https://metacpan.org/pod/Class::XSAccessor> since
the original module depends on XS (which PerlOnJava does not support).

The public API matches upstream: C<use Class::XSAccessor> with any of
C<accessors>, C<getters>, C<setters>, C<lvalue_accessors>, C<predicates>,
C<defined_predicates>, C<exists_predicates>, C<constructor>,
C<constructors>, C<true>, C<false>, C<chained>, C<replace>, C<class>.
See the upstream documentation for semantics.

The only observable difference from the XS version is execution speed.

=head1 SEE ALSO

L<Class::XSAccessor::Array>, L<Class::XSAccessor::Heavy>

=cut
