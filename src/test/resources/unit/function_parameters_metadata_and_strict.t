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
    required_name => { defaults => 'function', name => 'required' },
    prohibited_name => { defaults => 'function', name => 'prohibited' },
    no_defaults => { defaults => 'function', default_arguments => 0 },
    method => { name => 'required', shift => '$configured_self' },
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
required_name named_required() { 1 }
my $required_name_error = eval q{ required_name () {} };
ok(!$required_name_error, 'required-name keyword rejects anonymous declarations');
like($@, qr/expecting a function name/, 'required-name diagnostic identifies missing name');
my $prohibited_name_error = eval q{ prohibited_name named_forbidden() {} };
ok(!$prohibited_name_error, 'prohibited-name keyword rejects named declarations');
like($@, qr/expecting a parameter list/, 'prohibited-name diagnostic identifies expected parameter list');
my $default_argument_error = eval q{ no_defaults no_default($value = 42) { $value } };
ok(!$default_argument_error, 'default_arguments false rejects signature defaults');
like($@, qr/no_defaults.*default argument/i,
    'default argument diagnostic names its configured keyword');
method configured_default_method($key) { $configured_self->{$key} }
is(configured_default_method({ key => 'method-default' }, 'key'), 'method-default',
    'method keyword defaults to method semantics without an explicit defaults option');
my @anonymous_default_calls;
my $anonymous_defined_or = fun ($ //= push(@anonymous_default_calls, 'defined-or')) {
    scalar @anonymous_default_calls;
};
is($anonymous_defined_or->('present'), 0,
    'anonymous defined-or default does not run for a defined argument');
is($anonymous_defined_or->(undef), 1,
    'anonymous defined-or default runs for an undef argument');
my @named_default_calls;
my $named_assignment_default = fun (:$value = (push(@named_default_calls, 'assignment'), 'fallback')) {
    $value;
};
is($named_assignment_default->(value => undef), undef,
    'named assignment default preserves an explicitly supplied undef');
is_deeply(\@named_default_calls, [], 'named assignment default stays unevaluated for a present key');
is($named_assignment_default->(), 'fallback', 'named assignment default fills an absent key');
is_deeply(\@named_default_calls, ['assignment'], 'named assignment default runs once for an absent key');
my $positional_and_named = fun ($head, :$tail) { [$head, $tail] };
is_deeply($positional_and_named->('head-value', tail => 'tail-value'),
    ['head-value', 'tail-value'],
    'named parameter extraction skips leading positional arguments');
my $optional_positional_and_named = fun ($head = 'head-default', :$tail = 'tail-default') {
    [$head, $tail];
};
is_deeply($optional_positional_and_named->(), ['head-default', 'tail-default'],
    'optional positional defaults leave an empty named-argument tail');
my $required_named = fun (:$required) { $required };
my $required_named_error = eval { $required_named->(); 1 };
ok(!$required_named_error, 'required named parameter rejects an omitted argument');
like($@, qr/Too few arguments/, 'missing required named parameter reports too few arguments');
my $required_named_slurp = fun (:$key, @rest) { [$key, \@rest] };
is_deeply($required_named_slurp->(key => 'value'), ['value', []],
    'named slurpy parameter accepts the required key and an empty remainder');
my $required_named_slurp_missing = eval { $required_named_slurp->(other => 'value'); 1 };
ok(!$required_named_slurp_missing, 'named slurpy parameter requires its named key');
like($@, qr/missing named.*key/i, 'named slurpy omission identifies the missing key');
my $optional_named_only = fun (:$tail = 'tail-default') { $tail };
my $odd_named_error = eval { $optional_named_only->('orphan'); 1 };
ok(!$odd_named_error, 'named parameters reject an odd key/value tail');
like($@, qr/Odd number/, 'odd named argument diagnostic follows Perl wording');
eval q{ fun (:$duplicate, :$duplicate) {} };
like($@, qr/\$duplicate.*twice/, 'duplicate named parameter names identify the repeated parameter');
eval q{ fun (:$named_first, $positional_after) {} };
like($@, qr/positional.*named/i, 'positional-after-named diagnostic identifies both parameter kinds');
eval q{ method named_invocant(:$named_invocant:) {} };
like($@, qr/invocant.*\$named_invocant.*named/i,
    'named method invocant receives a focused diagnostic');
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

{
    my $default = 'outer';
    fun same_named_default($default = $default) { $default }
    is(same_named_default(), 'outer',
        'a default can read an outer lexical with the same name as its parameter');
    $default = 'changed';
    is(same_named_default(), 'changed',
        'same-named default closure observes later outer lexical values');
}

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

my $invalid_import_tag = eval { Function::Parameters->import(':not_exported'); 1 };
ok(!$invalid_import_tag, 'reject an unknown Function::Parameters import tag');
like($@, qr/not exported/, 'unknown import tag has a clear diagnostic');
my $invalid_import_keyword = eval { Function::Parameters->import({ '42' => 'function' }); 1 };
ok(!$invalid_import_keyword, 'reject an invalid configured keyword identifier');
like($@, qr/valid identifier/, 'invalid keyword identifier has a clear diagnostic');
my $invalid_import_type = eval { Function::Parameters->import({ bad_type => 'QQQQ' }); 1 };
ok(!$invalid_import_type, 'reject an invalid configured keyword type');
like($@, qr/valid type/, 'invalid keyword type has a clear diagnostic');

done_testing;
