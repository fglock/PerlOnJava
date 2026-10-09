use strict;
use warnings;
use Test::More;

# A reference to a read-only scalar is still a SCALAR reference.
our $readonly_package = 'value';
Internals::SvREADONLY($readonly_package, 1);
is ref(\$readonly_package), 'SCALAR',
    'a reference to a read-only package scalar reports SCALAR';

my $readonly_lexical = 'value';
Internals::SvREADONLY($readonly_lexical, 1);
is ref(\$readonly_lexical), 'SCALAR',
    'a reference to a read-only lexical reports SCALAR';

{
    no strict 'refs';
    our $symbolic = 'value';
    Internals::SvREADONLY(${'main::symbolic'}, 1);
    is ref(\${'main::symbolic'}), 'SCALAR',
        'a symbolic reference to a read-only package scalar reports SCALAR';
}

sub read_only_target { 1 }
our $readonly_code = \&read_only_target;
Internals::SvREADONLY($readonly_code, 1);
is ref(\$readonly_code), 'REF',
    'a reference to a read-only scalar holding a code reference reports REF';

our $readonly_array = [1, 2];
Internals::SvREADONLY($readonly_array, 1);
is ref(\$readonly_array), 'REF',
    'a reference to a read-only scalar holding an array reference reports REF';

our $readonly_object = bless {}, 'ReadOnlyHolder';
Internals::SvREADONLY($readonly_object, 1);
is ref(\$readonly_object), 'REF',
    'a reference to a read-only scalar holding a blessed reference reports REF';

is ref(\'literal'), 'SCALAR', 'a reference to a literal reports SCALAR';
is ref(\42), 'SCALAR', 'a reference to a numeric literal reports SCALAR';

my $exports = {};
$exports->{'$readonly_package'} = \$readonly_package;
is ref($exports->{'$readonly_package'}), 'SCALAR',
    'a read-only scalar reference survives storage in a hash';
is ${$exports->{'$readonly_package'}}, 'value',
    'the stored read-only reference still reads its value';

done_testing;
