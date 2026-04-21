package Class::XSAccessor::Array;
use 5.008;
use strict;
use warnings;
use Carp qw/croak/;
use Class::XSAccessor;
use Class::XSAccessor::Heavy;

our $VERSION = '1.19';

# PerlOnJava note: pure-Perl port. See Class::XSAccessor for rationale.

sub import {
    my $own_class = shift;
    my ($caller_pkg) = caller();

    my %opts = ref($_[0]) eq 'HASH' ? %{$_[0]} : @_;

    $caller_pkg = $opts{class} if defined $opts{class};

    my $read_subs      = $opts{getters}          || {};
    my $set_subs       = $opts{setters}          || {};
    my $acc_subs       = $opts{accessors}        || {};
    my $lvacc_subs     = $opts{lvalue_accessors} || {};
    my $pred_subs      = $opts{predicates}       || {};
    my $construct_subs = $opts{constructors}
        || [defined($opts{constructor}) ? $opts{constructor} : ()];
    my $true_subs      = $opts{true}  || [];
    my $false_subs     = $opts{false} || [];

    foreach my $subtype (["getter",          $read_subs],
                         ["setter",          $set_subs],
                         ["accessor",        $acc_subs],
                         ["lvalue_accessor", $lvacc_subs],
                         ["predicate",       $pred_subs])
    {
        my $subs = $subtype->[1];
        foreach my $subname (keys %$subs) {
            my $array_index = $subs->{$subname};
            _generate_method($caller_pkg, $subname, $array_index, \%opts, $subtype->[0]);
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
    my ($caller_pkg, $subname, $array_index, $opts, $type) = @_;

    croak("Cannot use undef as a array index for generating an XS $type accessor. (Sub: $subname)")
        if not defined $array_index;

    $subname = "${caller_pkg}::$subname" if $subname !~ /::/;

    Class::XSAccessor::Heavy::check_sub_existence($subname) if not $opts->{replace};

    my $code = _build_accessor($type, $array_index, $opts);

    no strict 'refs';
    no warnings 'redefine';
    *{$subname} = $code;
}

# Array-based accessor closures. These access $_[0]->[$index] rather
# than $_[0]->{$key}.
sub _build_accessor {
    my ($type, $idx, $opts) = @_;
    my $chained = $opts->{chained} ? 1 : 0;

    if ($type eq 'getter') {
        return sub {
            croak(sprintf('Usage: %s(self)', (caller(0))[3]))
                if @_ != 1;
            $_[0]->[$idx];
        };
    }
    elsif ($type eq 'setter') {
        return $chained
            ? sub { $_[0]->[$idx] = $_[1]; $_[0] }
            : sub { $_[0]->[$idx] = $_[1] };
    }
    elsif ($type eq 'accessor') {
        return $chained
            ? sub {
                if (@_ > 1) { $_[0]->[$idx] = $_[1]; return $_[0] }
                return $_[0]->[$idx];
              }
            : sub {
                $_[0]->[$idx] = $_[1] if @_ > 1;
                $_[0]->[$idx];
              };
    }
    elsif ($type eq 'lvalue_accessor') {
        return eval 'sub : lvalue { $_[0]->[$idx] }';
    }
    elsif ($type eq 'predicate') {
        return sub { defined $_[0]->[$idx] };
    }
    elsif ($type eq 'constructor') {
        # Array-based: create an empty array ref blessed into the class.
        return sub {
            my $class = shift;
            return bless [], ref($class) || $class;
        };
    }
    elsif ($type eq 'true') {
        return sub () { 1 };
    }
    elsif ($type eq 'false') {
        return sub () { !1 };
    }
    else {
        croak("Unknown Class::XSAccessor::Array sub type: $type");
    }
}

1;

__END__

=head1 NAME

Class::XSAccessor::Array - Generate accessors for array-based objects
(PerlOnJava pure-Perl port)

=head1 DESCRIPTION

Pure-Perl re-implementation for PerlOnJava. See
L<Class::XSAccessor::Array> upstream for the full API documentation.

=cut
