use strict;
use warnings;
use Test::More;

use Function::Parameters {
    fun => {},
    lax_count => { check_argument_count => 0 },
    lvalue_function => { attributes => ':lvalue' },
};

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
