use strict;
use warnings;
use Test::More;

# Regression test for: custom isa method using list assignment from @_
# corrupts the caller's variable after an exception is thrown.
#
# When a method calls $_[0]->some_method(...) where some_method uses
# my ($self, $class) = @_, and the calling method then does shift+die/croak,
# the original caller's variable should be unchanged.

package MyClass;
sub new { bless { val => $_[1] }, $_[0] }
sub my_isa_list  { my ($s,$c) = @_; UNIVERSAL::isa($s,$c) }
sub my_isa_shift { my $s = shift; my $c = shift; UNIVERSAL::isa($s,$c) }
sub my_isa_pass  { UNIVERSAL::isa(@_) }

package main;
use Carp;

# Helper: define test method on MyClass, call it, return state of $obj after
sub run_test {
    my ($method_sub, $label) = @_;
    no warnings 'redefine';
    *MyClass::_tester = $method_sub;
    my $obj = MyClass->new(42);
    eval { $obj->_tester() };
    return defined($obj) ? $obj->{val} : undef;
}

# Test with list-assignment isa: my ($s,$c) = @_
my $result = run_test(sub {
    unless ($_[0]->my_isa_list('MyClass')) {}
    my $self = shift;
    Carp::croak("died");
}, 'isa_list');
is($result, 42, 'caller variable preserved after croak when isa uses list assignment');

# Test with shift-based isa: my $s = shift; my $c = shift
$result = run_test(sub {
    unless ($_[0]->my_isa_shift('MyClass')) {}
    my $self = shift;
    Carp::croak("died");
}, 'isa_shift');
is($result, 42, 'caller variable preserved after croak when isa uses shift');

# Test with pass-through isa: UNIVERSAL::isa(@_)
$result = run_test(sub {
    unless ($_[0]->my_isa_pass('MyClass')) {}
    my $self = shift;
    Carp::croak("died");
}, 'isa_pass');
is($result, 42, 'caller variable preserved after croak when isa uses passthrough');

# Verify the same holds without the isa condition (sanity check)
$result = run_test(sub {
    my $self = shift;
    Carp::croak("died");
}, 'no_isa');
is($result, 42, 'caller variable preserved after croak without isa check');

# Verify behavior when there's no croak but isa is called (sanity check)
{
    no warnings 'redefine';
    *MyClass::_tester = sub {
        unless ($_[0]->my_isa_list('MyClass')) {}
        my $self = shift;
        $self->{val} = 99;
        return $self;
    };
    my $obj = MyClass->new(42);
    $obj->_tester();
    is($obj->{val}, 99, 'normal execution with isa check works');
}

# Math::BigFloat specific scenario (the original failure)
# Only test with bundled Math::BigFloat (may not work with all system Perl versions)
SKIP: {
    eval {
        require Math::BigFloat;
        Math::BigFloat->config( trap_nan => 1 );
        Math::BigFloat->config( trap_nan => 0 );
    };
    skip 'Math::BigFloat config not available', 4 if $@;

    Math::BigFloat->config( trap_nan => 1 );

    my $x = Math::BigFloat->new('42');
    eval { $x->bnan() };
    is($x, 42, 'BigFloat: $x preserved after bnan() with trap_nan=1');

    $x = Math::BigFloat->new('4711');
    eval { $x->binf() };
    is($x, 4711, 'BigFloat: $x preserved after binf() with trap_inf=0, trap_nan=1');

    Math::BigFloat->config( trap_inf => 1 );

    $x = Math::BigFloat->new('4711');
    eval { $x->binf() };
    is($x, 4711, 'BigFloat: $x preserved after binf() with trap_inf=1');

    $x = Math::BigFloat->new('4711');
    eval { $x = Math::BigFloat->new('inf') };
    is($x, 4711, 'BigFloat: $x unchanged after new("inf") throws with trap_inf=1');

    Math::BigFloat->config( trap_nan => 0, trap_inf => 0 );
}

done_testing;
