use strict;
use warnings;
use Test::More;

use Function::Parameters {
    fun => {},
    lax_count => { check_argument_count => 0 },
    lvalue_function => { attributes => ':lvalue' },
    runtime_fun => { defaults => 'function_strict', runtime => 1 },
    runtime_method => { defaults => 'method_strict', runtime => 1 },
    runtime_installed => {
        defaults => 'function_strict', runtime => 1, install_sub => 'capture_installer',
    },
    required_modifier => { defaults => 'before', runtime => 1 },
    multi_method => { defaults => 'method', shift => ['$self1', '$self2'] },
    single_method => { defaults => 'method' },
};

our ($installed_name, $installed_code);
sub capture_installer { ($installed_name, $installed_code) = @_ }

runtime_installed installed_by_callback($value) { $value }
is($installed_name, 'installed_by_callback',
    'runtime install_sub callback receives the declared name');
is($installed_code->('callback-ready'), 'callback-ready',
    'runtime install_sub callback receives the function body');
multi_method checked_multi_method($value) { $value }
my $multi_method_error = eval { checked_multi_method('only-one-argument'); 1 };
ok(!$multi_method_error, 'configured multi-invocant method checks argument count');
like($@, qr/^Too few arguments for multi_method checked_multi_method \(expected 3, got 1\)/,
    'multi-invocant argument error names the keyword and expected count');
eval q{ multi_method ($self1) {} };
like($@, qr/\$self1 can't appear twice in the same parameter list/,
    'configured invocant cannot be repeated as an ordinary parameter');
eval q{ multi_method wrong_invocants($self:) {} };
like($@, qr/^In multi_method wrong_invocants: number of invocants in parameter list \(1\) differs from number of invocants in keyword definition \(2\)/,
    'configured method reports an invocant count mismatch');
eval q{ fun ($self:) {} };
like($@, qr/invocant \$self not allowed here/,
    'function keyword rejects an explicit invocant with a focused diagnostic');
eval q{ single_method (@items:) {} };
like($@, qr/invocant \@items can't be an array/,
    'method keyword rejects an array invocant with a focused diagnostic');
my $unnamed_modifier_result = eval q{ required_modifier () {} };
ok(!$unnamed_modifier_result, 'modifier keyword requires a named declaration');
like($@, qr/expecting a function name/,
    'unnamed modifier diagnostic identifies the missing function name');
ok(!defined(&runtime_named), 'runtime keyword has no CV before its declaration executes');
runtime_fun runtime_named($value) { $value }
ok(defined(&runtime_named), 'runtime keyword installs its CV when executed');
is(runtime_named('ready'), 'ready', 'runtime-installed function is callable');
runtime_fun runtime_caller_name() { (caller 0)[3] }
is(runtime_caller_name(), 'main::runtime_caller_name',
    'runtime-installed function retains its declared caller name');

{
    package FunctionParametersRuntimeMethod;
    runtime_method echo($value) { $value }
    runtime_method caller_name() { (caller 0)[3] }
}
my $runtime_method_object = bless {}, 'FunctionParametersRuntimeMethod';
is($runtime_method_object->echo('method-ready'), 'method-ready',
    'runtime-installed method binds its invocant');
is($runtime_method_object->caller_name(), 'FunctionParametersRuntimeMethod::caller_name',
    'runtime-installed method retains its declared caller name');

{
    package FunctionParametersRuntimeConditional;
    if (0) { runtime_fun skipped() { 1 } }
}
ok(!defined(&FunctionParametersRuntimeConditional::skipped),
    'runtime declaration in an unexecuted branch remains undefined');

{
    package FunctionParametersRuntimeAlias::Base;
    runtime_method value() { 'before' }
    package FunctionParametersRuntimeAlias::Alias;
    package FunctionParametersRuntimeAlias::Derived;
    our @ISA = ('FunctionParametersRuntimeAlias::Alias');
}
*FunctionParametersRuntimeAlias::Alias::value =
    *FunctionParametersRuntimeAlias::Base::value;
my $runtime_aliased_method = bless {}, 'FunctionParametersRuntimeAlias::Derived';
is($runtime_aliased_method->value(), 'before',
    'runtime method resolves through a whole-glob alias');
{
    package FunctionParametersRuntimeAlias::Base;
    no warnings 'redefine';
    runtime_method value() { 'after' }
}
is($runtime_aliased_method->value(), 'after',
    'runtime redefinition updates method dispatch through whole-glob aliases');

sub ordinary_sub { return }
ok(!defined(Function::Parameters::info(\&ordinary_sub)),
    'introspection returns undef for a non-Function::Parameters sub');

fun inspected_before_call($required, $optional = 1) { $required }
my $info = Function::Parameters::info(\&inspected_before_call);
is($info->keyword, 'fun', 'introspection works before the function is called');
is_deeply([map { "$_" } $info->positional_required], ['$required'],
    'pre-call metadata includes required parameters');
is_deeply([map { "$_" } $info->positional_optional], ['$optional'],
    'pre-call metadata includes optional parameters');
ok(!defined(($info->positional_required)[0]->type),
    'untyped parameters report no type constraint');
my $anonymous_info = Function::Parameters::info(
    fun ($anonymous_required, $anonymous_optional = 1) { $anonymous_required });
is($anonymous_info->keyword, 'fun', 'anonymous declarations retain signature metadata');
is_deeply([map { "$_" } $anonymous_info->positional_optional], ['$anonymous_optional'],
    'anonymous metadata includes optional parameters');

lax_count permissive_count($value) { $value }
is(permissive_count(1, 2), 1,
    'check_argument_count false allows extra arguments');
lvalue_function lvalue_value($reference) { $$reference }
my $mutable_value = 'before';
lvalue_value(\$mutable_value) = 'after';
is($mutable_value, 'after', 'configured lvalue attributes reach the subroutine');

{
    package FunctionParametersAmbiguousCore;
    fun join($left, $right) { "$left:$right" }
}
my ($ambiguous_core_call, $ambiguous_core_warning);
{
    local $SIG{__WARN__} = sub { $ambiguous_core_warning .= shift };
    $ambiguous_core_call = eval q{
        package FunctionParametersAmbiguousCore;
        use warnings;
        join('left', 'right');
    };
}
is($ambiguous_core_call, 'right', 'the ambiguous call resolves to the core operator');
like($ambiguous_core_warning, qr/Ambiguous call resolved as CORE::join/,
    'declared Function::Parameters sub triggers the core ambiguity warning');
my $typed_declaration = eval q{
    use Types::Standard qw(Int);
    use Function::Parameters { typed_for_info => { defaults => 'function' } };
    typed_for_info inspected_typed_parameter(Int $typed) { $typed }
    1;
};
ok($typed_declaration, 'typed Function::Parameters declaration compiles');
my $typed_info = Function::Parameters::info(\&inspected_typed_parameter);
is(ref(($typed_info->positional_required)[0]->type), 'Type::Tiny',
    'parameter metadata exposes its declared type object before the function is called');
is(($typed_info->positional_required)[0]->type->name, 'Int',
    'parameter metadata preserves the declared type object');

my $slurpy_scalar_error = eval q{
    use Function::Parameters;
    fun invalid_after_array(@items, $later) { $later }
    1;
};
ok(!$slurpy_scalar_error, 'strict mode rejects a scalar after an array slurpy');
like($@, qr/"\$later" can't appear after slurpy parameter "\@items"/,
    'strict mode names the later and slurpy parameters');

my $slurpy_hash_error = eval q{
    use Function::Parameters;
    fun invalid_after_hash(%options, $later) { $later }
    1;
};
ok(!$slurpy_hash_error, 'strict mode rejects a scalar after a hash slurpy');
like($@, qr/"\$later" can't appear after slurpy parameter "%options"/,
    'hash slurpy diagnostic names both parameters');

my $second_slurpy_error = eval q{
    use Function::Parameters;
    fun invalid_second_slurpy(@items, @more) { @more }
    1;
};
ok(!$second_slurpy_error, 'strict mode rejects a second slurpy parameter');
like($@, qr/"\@more" can't appear after slurpy parameter "\@items"/,
    'second slurpy diagnostic names both parameters');

my $invalid_rarity = eval q{
    use Function::Parameters {
        spike => { rarity => 'best' },
    };
    1;
};
ok(!$invalid_rarity, 'reject an unsupported rarity option');
like($@, qr/Invalid.*rarity/i, 'unsupported rarity has a clear diagnostic');

my $invalid_attributes = eval q{
    use Function::Parameters { broken => { attributes => 'nope' } };
    1;
};
ok(!$invalid_attributes, 'reject a malformed configured attribute list');
like($@, qr/nope.*attributes/i, 'malformed configured attributes have a clear diagnostic');

my $invalid_attribute_syntax = eval q~
    use Function::Parameters { broken => { attributes => ': in valid {' } };
    1;
~;
ok(!$invalid_attribute_syntax, 'reject invalid configured attribute syntax');
like($@, qr/in valid.*attributes/i, 'invalid configured attribute syntax has a clear diagnostic');

done_testing;
