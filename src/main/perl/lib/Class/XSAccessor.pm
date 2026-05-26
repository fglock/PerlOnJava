package Class::XSAccessor;

use 5.008;
use strict;
use warnings;
use Carp qw(croak);
use Scalar::Util qw(reftype);
use Class::XSAccessor::Heavy;

our $VERSION = '1.19';

sub _make_hash {
    my $ref = shift;

    if (ref($ref)) {
        if (ref($ref) eq 'ARRAY') {
            $ref = { map { $_ => $_ } @$ref };
        }
    }
    else {
        $ref = { $ref, $ref };
    }

    return $ref;
}

sub import {
    my $own_class = shift;
    my ($caller_pkg) = caller();

    my %opts = ref($_[0]) eq 'HASH' ? %{ $_[0] } : @_;
    $caller_pkg = $opts{class} if defined $opts{class};

    my $read_subs      = _make_hash($opts{getters} || {});
    my $set_subs       = _make_hash($opts{setters} || {});
    my $acc_subs       = _make_hash($opts{accessors} || {});
    my $lvacc_subs     = _make_hash($opts{lvalue_accessors} || {});
    my $pred_subs      = _make_hash($opts{predicates} || {});
    my $ex_pred_subs   = _make_hash($opts{exists_predicates} || {});
    my $def_pred_subs  = _make_hash($opts{defined_predicates} || {});
    my $test_subs      = _make_hash($opts{__tests__} || {});
    my $construct_subs = $opts{constructors}
        || [ defined($opts{constructor}) ? $opts{constructor} : () ];
    my $true_subs      = $opts{true} || [];
    my $false_subs     = $opts{false} || [];

    for my $subtype (
        [ getter          => $read_subs ],
        [ setter          => $set_subs ],
        [ accessor        => $acc_subs ],
        [ lvalue_accessor => $lvacc_subs ],
        [ test            => $test_subs ],
        [ ex_predicate    => $ex_pred_subs ],
        [ def_predicate   => $def_pred_subs ],
        [ def_predicate   => $pred_subs ],
    ) {
        my ($type, $subs) = @$subtype;
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
    my ($caller_pkg, $subname, $hashkey, $opts, $type) = @_;

    croak("Cannot use undef as a hash key for generating an XS $type accessor. (Sub: $subname)")
        if not defined $hashkey;

    $subname = "${caller_pkg}::$subname" if $subname !~ /::/;

    Class::XSAccessor::Heavy::check_sub_existence($subname) if not $opts->{replace};
    no warnings 'redefine';

    if ($type eq 'getter') {
        newxs_getter($subname, $hashkey);
    }
    elsif ($type eq 'lvalue_accessor') {
        newxs_lvalue_accessor($subname, $hashkey);
    }
    elsif ($type eq 'setter') {
        newxs_setter($subname, $hashkey, $opts->{chained} || 0);
    }
    elsif ($type eq 'def_predicate') {
        newxs_defined_predicate($subname, $hashkey);
    }
    elsif ($type eq 'ex_predicate') {
        newxs_exists_predicate($subname, $hashkey);
    }
    elsif ($type eq 'constructor') {
        newxs_constructor($subname);
    }
    elsif ($type eq 'true') {
        newxs_boolean($subname, 1);
    }
    elsif ($type eq 'false') {
        newxs_boolean($subname, 0);
    }
    elsif ($type eq 'test') {
        newxs_test($subname, $hashkey);
    }
    else {
        newxs_accessor($subname, $hashkey, $opts->{chained} || 0);
    }
}

sub _usage {
    my ($name, $args) = @_;
    $name =~ s/^.*:://;
    croak("Usage: $name($args)");
}

sub _check_hash_invocant {
    my ($self) = @_;
    croak('Class::XSAccessor: invalid instance method invocant: no hash ref supplied')
        if !ref($self) || (reftype($self) || q{}) ne 'HASH';
}

sub _install {
    my ($name, $code) = @_;
    no strict 'refs';
    no warnings 'redefine';
    *{$name} = $code;
}

sub newxs_getter {
    my ($name, $key) = @_;
    _install($name, sub {
        @_ == 1 or _usage($name, 'self');
        _check_hash_invocant($_[0]);
        return $_[0]->{$key};
    });
}

sub newxs_lvalue_accessor {
    my ($name, $key) = @_;
    _install($name, sub : lvalue {
        @_ == 1 or _usage($name, 'self');
        _check_hash_invocant($_[0]);
        $_[0]->{$key};
    });
}

sub newxs_setter {
    my ($name, $key, $chained) = @_;
    _install($name, sub {
        @_ == 2 or _usage($name, 'self, newvalue');
        _check_hash_invocant($_[0]);
        $_[0]->{$key} = $_[1];
        return $chained ? $_[0] : $_[1];
    });
}

sub newxs_accessor {
    my ($name, $key, $chained) = @_;
    _install($name, sub {
        @_ >= 1 or _usage($name, 'self, ...');
        _check_hash_invocant($_[0]);
        if (@_ > 1) {
            $_[0]->{$key} = $_[1];
            return $chained ? $_[0] : $_[1];
        }
        return $_[0]->{$key};
    });
}

sub newxs_predicate {
    goto &newxs_defined_predicate;
}

sub newxs_defined_predicate {
    my ($name, $key) = @_;
    _install($name, sub {
        @_ == 1 or _usage($name, 'self');
        _check_hash_invocant($_[0]);
        return defined $_[0]->{$key} ? 1 : q{};
    });
}

sub newxs_exists_predicate {
    my ($name, $key) = @_;
    _install($name, sub {
        @_ == 1 or _usage($name, 'self');
        _check_hash_invocant($_[0]);
        return exists $_[0]->{$key} ? 1 : q{};
    });
}

sub newxs_constructor {
    my ($name) = @_;
    _install($name, sub {
        my $class = shift;
        croak('Uneven number of arguments to constructor.') if @_ % 2;
        return bless { @_ }, ref($class) || $class;
    });
}

sub newxs_boolean {
    my ($name, $truth) = @_;
    _install($name, sub {
        @_ == 1 or _usage($name, 'self');
        return $truth ? 1 : q{};
    });
}

sub newxs_test {
    my ($name, $key) = @_;
    newxs_accessor($name, $key, 0);
}

sub __entersub_optimized__ {
    return 0;
}

1;
