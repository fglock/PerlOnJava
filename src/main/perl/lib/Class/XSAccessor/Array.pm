package Class::XSAccessor::Array;

use 5.008;
use strict;
use warnings;
use Carp qw(croak);
use Scalar::Util qw(reftype);
use Class::XSAccessor ();
use Class::XSAccessor::Heavy;

our $VERSION = '1.19';

sub import {
    my $own_class = shift;
    my ($caller_pkg) = caller();

    my %opts = ref($_[0]) eq 'HASH' ? %{ $_[0] } : @_;
    $caller_pkg = $opts{class} if defined $opts{class};

    my $read_subs      = $opts{getters} || {};
    my $set_subs       = $opts{setters} || {};
    my $acc_subs       = $opts{accessors} || {};
    my $lvacc_subs     = $opts{lvalue_accessors} || {};
    my $pred_subs      = $opts{predicates} || {};
    my $construct_subs = $opts{constructors}
        || [ defined($opts{constructor}) ? $opts{constructor} : () ];
    my $true_subs      = $opts{true} || [];
    my $false_subs     = $opts{false} || [];

    for my $subtype (
        [ getter          => $read_subs ],
        [ setter          => $set_subs ],
        [ accessor        => $acc_subs ],
        [ lvalue_accessor => $lvacc_subs ],
        [ predicate       => $pred_subs ],
    ) {
        my ($type, $subs) = @$subtype;
        $subs = Class::XSAccessor::_make_hash($subs);
        for my $subname (keys %$subs) {
            _generate_method($caller_pkg, $subname, $subs->{$subname}, \%opts, $type);
        }
    }

    for my $subtype (
        [ constructor => $construct_subs ],
        [ true        => $true_subs ],
        [ false       => $false_subs ],
    ) {
        my ($type, $subs) = @$subtype;
        for my $subname (@$subs) {
            _generate_method($caller_pkg, $subname, q{}, \%opts, $type);
        }
    }
}

sub _generate_method {
    my ($caller_pkg, $subname, $array_index, $opts, $type) = @_;

    croak("Cannot use undef as a array index for generating an XS $type accessor. (Sub: $subname)")
        if not defined $array_index;

    $subname = "${caller_pkg}::$subname" if $subname !~ /::/;

    Class::XSAccessor::Heavy::check_sub_existence($subname) if not $opts->{replace};
    no warnings 'redefine';

    if ($type eq 'getter') {
        newxs_getter($subname, $array_index);
    }
    elsif ($type eq 'lvalue_accessor') {
        newxs_lvalue_accessor($subname, $array_index);
    }
    elsif ($type eq 'setter') {
        newxs_setter($subname, $array_index, $opts->{chained} || 0);
    }
    elsif ($type eq 'predicate') {
        newxs_predicate($subname, $array_index);
    }
    elsif ($type eq 'constructor') {
        newxs_constructor($subname);
    }
    elsif ($type eq 'true') {
        Class::XSAccessor::newxs_boolean($subname, 1);
    }
    elsif ($type eq 'false') {
        Class::XSAccessor::newxs_boolean($subname, 0);
    }
    else {
        newxs_accessor($subname, $array_index, $opts->{chained} || 0);
    }
}

sub _check_array_invocant {
    my ($self) = @_;
    croak('Class::XSAccessor: invalid instance method invocant: no array ref supplied')
        if !ref($self) || (reftype($self) || q{}) ne 'ARRAY';
}

sub _usage {
    goto &Class::XSAccessor::_usage;
}

sub _install {
    my ($name, $code) = @_;
    no strict 'refs';
    no warnings 'redefine';
    *{$name} = $code;
}

sub newxs_getter {
    my ($name, $index) = @_;
    _install($name, sub {
        @_ == 1 or _usage($name, 'self');
        _check_array_invocant($_[0]);
        return $_[0]->[$index];
    });
}

sub newxs_lvalue_accessor {
    my ($name, $index) = @_;
    _install($name, sub : lvalue {
        @_ == 1 or _usage($name, 'self');
        _check_array_invocant($_[0]);
        $_[0]->[$index];
    });
}

sub newxs_setter {
    my ($name, $index, $chained) = @_;
    _install($name, sub {
        @_ == 2 or _usage($name, 'self, newvalue');
        _check_array_invocant($_[0]);
        $_[0]->[$index] = $_[1];
        return $chained ? $_[0] : $_[1];
    });
}

sub newxs_accessor {
    my ($name, $index, $chained) = @_;
    _install($name, sub {
        @_ >= 1 or _usage($name, 'self, ...');
        _check_array_invocant($_[0]);
        if (@_ > 1) {
            $_[0]->[$index] = $_[1];
            return $chained ? $_[0] : $_[1];
        }
        return $_[0]->[$index];
    });
}

sub newxs_predicate {
    my ($name, $index) = @_;
    _install($name, sub {
        @_ == 1 or _usage($name, 'self');
        _check_array_invocant($_[0]);
        return defined $_[0]->[$index] ? 1 : q{};
    });
}

sub newxs_constructor {
    my ($name) = @_;
    _install($name, sub {
        my $class = shift;
        return bless [], ref($class) || $class;
    });
}

1;
