use strict;
use warnings;
use Test::More tests => 14;

sub install_argument {
    $StashReadonlyScalarRef::{'argument'} = \$_[0];
}

install_argument('argument value');
is($StashReadonlyScalarRef::argument, 'argument value',
    'stash assignment of a readonly argument aliases the scalar slot');
ok(!defined &StashReadonlyScalarRef::argument,
    'readonly argument reference does not create a code slot');

$StashReadonlyScalarRef::{'literal'} = \q[literal value];
is($StashReadonlyScalarRef::literal, 'literal value',
    'stash assignment of a literal reference aliases the scalar slot');
ok(!defined &StashReadonlyScalarRef::literal,
    'literal reference does not create a code slot');

sub install_reference_value {
    my ($name) = @_;
    $StashReadonlyScalarRef::{$name} = \$_[1];
}

my $object = bless {}, 'StashReadonlyScalarRef::Object';
install_reference_value('object', $object);
is(ref($StashReadonlyScalarRef::object), 'StashReadonlyScalarRef::Object',
    'stash assignment aliases a scalar containing an object');
ok(!defined &StashReadonlyScalarRef::object,
    'object-valued scalar reference does not create a code slot');

my $array = [];
install_reference_value('array', $array);
is(ref($StashReadonlyScalarRef::array), 'ARRAY',
    'stash assignment aliases a scalar containing another reference');
ok(!defined &StashReadonlyScalarRef::array,
    'reference-valued scalar reference does not create a code slot');

use constant PSEUDO_CONSTANT => 'constant value';
is(PSEUDO_CONSTANT, 'constant value',
    'explicit readonly pseudo-constants retain constant.pm behavior');

$::{'@'} = \3;
my $readonly_error_before_eval = $@;
eval {};
is($readonly_error_before_eval, 3,
    'stash assignment can alias a literal to the error variable');
is($@, '', 'successful eval can clear a read-only error-variable alias');
eval { die "caught\n" };
is($@, "caught\n", 'failed eval can replace a read-only error-variable alias');

$::{'@'} = \4;
my $readonly_error_before_string_eval = $@;
eval q{};
is($readonly_error_before_string_eval, 4,
    'stash assignment can re-alias a literal before string eval');
is($@, '', 'successful string eval can clear a read-only error-variable alias');
