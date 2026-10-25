use strict;
use warnings;
use Test::More;

my $loaded = eval { require Function::Parameters; 1 };
if (!$loaded && $@ =~ /Can't locate Function\/Parameters\.pm/) {
    plan skip_all => 'Function::Parameters is not installed in system Perl';
}
die $@ unless $loaded;

my $configured = eval q{
    use Function::Parameters {
        configured_function => 'function',
        configured_classmethod => 'classmethod',
        configured_method => {
            defaults => 'method',
            shift => ['$first_invocant', '$second_invocant'],
        },
    };
    configured_function custom_alias($value) { $value + 6 }
    configured_classmethod class_name() { $class }
    configured_method two_invocants($value) {
        join ':', $first_invocant, $second_invocant, $value, scalar @_;
    }
    configured_method explicit_two_invocants($first, $second: $value) {
        join ':', $first, $second, $value, scalar @_;
    }
    1;
};
die $@ unless $configured;

is(custom_alias(4), 10, 'hash configuration registers a function alias');
is(class_name('Example::Class'), 'Example::Class',
    'a configured classmethod binds its implicit $class invocant');
is(two_invocants('one', 'two', 'three'), 'one:two:three:1',
    'a configured method binds multiple invocants and removes them from @_');
is(explicit_two_invocants('left', 'right', 'value'), 'left:right:value:1',
    'a multi-invocant method accepts explicit invocant names');
my @multi_invocants = Function::Parameters::info(\&two_invocants)->invocants;
is_deeply([map { "$_" } @multi_invocants], ['$first_invocant', '$second_invocant'],
    'signature metadata reports all configured invocants');
is(Function::Parameters::info(\&two_invocants)->keyword, 'configured_method',
    'signature metadata preserves a configured keyword alias');

my $configured_no = eval q{
    no Function::Parameters qw(configured_function);
    configured_function unavailable_alias() { 1 }
    1;
};
ok(!$configured_no, 'no removes a selected configured keyword alias');

done_testing;
