use strict;
use warnings;
use Test::More;

sub immediate_scalar_value {
    my ($value) = @_;
    return $value + 1;
}

sub argument_mutation_keeps_lexical_copy {
    my ($value) = @_;
    $_[0] = 99;
    return $value;
}

sub lexical_reference_keeps_copy_cell {
    my ($value) = @_;
    my $reference = \$value;
    $_[0] = 88;
    return $$reference;
}

sub recursive_copy_cells {
    my ($value) = @_;
    return $value if $value == 0;
    return $value + recursive_copy_cells($value - 1);
}

sub string_eval_observes_lexical_copy {
    my ($value) = @_;
    $_[0] = 77;
    return eval q{$value};
}

{
    package DirectArgumentBindingGuardObject;
    sub DESTROY { ++$main::direct_argument_binding_destroyed }
}

sub lexical_copy_keeps_object_alive_through_body {
    my ($value) = @_;
    $_[0] = undef;
    return ref $value;
}

is(immediate_scalar_value(41), 42,
    'immediate scalar lexical use has ordinary copy value');

my $mutated = 5;
is(argument_mutation_keeps_lexical_copy($mutated), 5,
    'mutation through @_ does not change unpacked lexical');
is($mutated, 99, 'mutation through @_ still updates caller');

my $referenced = 6;
is(lexical_reference_keeps_copy_cell($referenced), 6,
    'reference to lexical retains its independent copied value');
is($referenced, 88, 'referenced lexical does not suppress @_ aliasing');

is(recursive_copy_cells(3), 6,
    'recursive entries retain distinct lexical copy cells');

my $evaluated = 7;
is(string_eval_observes_lexical_copy($evaluated), 7,
    'string eval observes the lexical copy rather than mutated @_');
is($evaluated, 77, 'string eval does not suppress caller aliasing');

our $direct_argument_binding_destroyed = 0;
my $object = bless {}, 'DirectArgumentBindingGuardObject';
is(lexical_copy_keeps_object_alive_through_body($object),
    'DirectArgumentBindingGuardObject',
    'lexical copy keeps argument object alive after @_ releases it');
ok(!defined $object, 'assignment through @_ releases caller object slot');
is($direct_argument_binding_destroyed, 1,
    'object is destroyed after the lexical copy leaves scope');

done_testing;
