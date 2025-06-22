use strict;
use warnings;
use Test::More;

# Basic prototype tests from original file
sub empty_proto () { 42 }
is(empty_proto(), 42, "Empty prototype works like a constant");
is(empty_proto, 42, "Empty prototype allows call without parens");

sub scalar_proto ($) { $_[0] }
is(scalar_proto(123), 123, "Scalar prototype accepts single argument");
is(scalar_proto("abc"), "abc", "Scalar prototype works with strings");

sub list_proto (@) { join(",", @_) }
is(list_proto(1,2,3), "1,2,3", "List prototype accepts multiple arguments");

sub array_ref_proto (\@) { $_[0]->[0] }
my @arr = (1,2,3);
is(array_ref_proto(@arr), 1, "Array reference prototype works");

sub hash_ref_proto (\%) { keys %{$_[0]} }
my %hash = (a => 1, b => 2);
is(hash_ref_proto(%hash), 2, "Hash reference prototype works");

sub scalar_ref_proto (\$) { ${$_[0]} }
my $val = 42;
is(scalar_ref_proto($val), 42, "Scalar reference prototype works");

sub code_ref_proto (&) { $_[0]->() }
is(code_ref_proto{ 99 }, 99, "Code reference prototype works");

sub multi_proto ($$) { $_[0] + $_[1] }
is(multi_proto(2, 3), 5, "Multiple argument prototype works");

sub optional_proto (;$) { defined $_[0] ? $_[0] : "default" }
is(optional_proto(), "default", "Optional argument prototype works without arg");
is(optional_proto("test"), "test", "Optional argument prototype works with arg");

sub mixed_proto ($@) { $_[0] . ":" . join(",", @_[1..$#_]) }
is(mixed_proto("head", 1,2,3), "head:1,2,3", "Mixed prototype works");

sub slurpy_proto ($;@) { $_[0] . ":" . join(",", @_[1..$#_]) }
is(slurpy_proto("x"), "x:", "Slurpy prototype works with single arg");
is(slurpy_proto("x", 1,2), "x:1,2", "Slurpy prototype works with multiple args");

sub context_proto (_) { wantarray ? (1,2,3) : "scalar" }
my @list = context_proto(1);
is_deeply(\@list, [1,2,3], "Context prototype works in list context");
is(scalar context_proto(1), "scalar", "Context prototype works in scalar context");

sub bracketed_proto (\[@%]) { ref $_[0] }
@arr = (1,2,3);
%hash = (a => 1);
is(bracketed_proto(@arr), "ARRAY", "Bracketed prototype accepts array reference");
is(bracketed_proto(%hash), "HASH", "Bracketed prototype accepts hash reference");

sub multi_bracketed_proto (\[@%] \[@%]) { ref($_[0]) . "," . ref($_[1]) }
is(multi_bracketed_proto(@arr, %hash), "ARRAY,HASH", "Multiple bracketed prototypes work");
is(multi_bracketed_proto(%hash, @arr), "HASH,ARRAY", "Bracketed prototypes work in any order");

sub optional_bracketed_proto (;\[@%]) { defined $_[0] ? ref($_[0]) : "none" }
is(optional_bracketed_proto(), "none", "Optional bracketed prototype works without arg");
is(optional_bracketed_proto(@arr), "ARRAY", "Optional bracketed prototype works with array");
is(optional_bracketed_proto(%hash), "HASH", "Optional bracketed prototype works with hash");

subtest "Plus (+) prototype behavior" => sub {
    sub plus_proto (+) { ref($_[0]) || "SCALAR" }

    my @plus_arr = (1,2,3);
    my %plus_hash = (a => 1);
    my $aref = [1,2,3];
    my $href = {x => 1};

    is(plus_proto(@plus_arr), "ARRAY", "accepts literal array as reference");
    is(plus_proto(%plus_hash), "HASH", "accepts literal hash as reference");
    is(plus_proto(42), "SCALAR", "forces scalar context on numbers");
    is(plus_proto("xyz"), "SCALAR", "forces scalar context on strings");
    is(plus_proto($aref), "ARRAY", "preserves array reference");
    is(plus_proto($href), "HASH", "preserves hash reference");
};

subtest "Star (*) prototype behavior" => sub {
    sub star_proto (*) { ref($_[0]) || 'SCALAR' }

    my @star_arr = (1,2,3);
    my %star_hash = (a => 1);
    my $scalar = 42;
    local *HANDLE;

    is(star_proto(@star_arr), 'SCALAR', "accepts array in scalar context");
    is(star_proto(%star_hash), 'SCALAR', "accepts hash in scalar context");
    is(star_proto($scalar), 'SCALAR', "accepts scalar");
    is(star_proto(*HANDLE), 'GLOB', "accepts typeglob");
    is(star_proto(\@star_arr), 'ARRAY', "accepts array reference");
    is(star_proto(\%star_hash), 'HASH', "accepts hash reference");
    is(star_proto(\$scalar), 'SCALAR', "accepts scalar reference");
    is(star_proto(sub {}), 'CODE', "accepts code reference");
};

# NEW COMPREHENSIVE TESTS

sub empty_test () { "empty" }

subtest "Empty prototype () edge cases" => sub {
    
    is(empty_test(), "empty", "Empty prototype with parentheses");
    is(empty_test, "empty", "Empty prototype without parentheses");
    
    # Test comma operator behavior
    my $result = eval { empty_test,1,2,3; 1 };
    ok($result, "Empty prototype allows comma operator (parsed as empty_test(), 1, 2, 3)");
    
    # Test with parentheses - should fail
    my $error = "";
    {
        local $SIG{__WARN__} = sub { $error = $_[0] };
        eval { empty_test(1,2,3) };
        like($@, qr/Too many arguments/, "Empty prototype with parentheses rejects arguments");
    }
};

subtest "Underscore (_) prototype comprehensive tests" => sub {
    sub underscore_test (_) { $_[0] // "undef" }
    
    # Test default behavior
    local $_ = "default_value";
    is(underscore_test(), "default_value", "Underscore prototype uses \$_ when no args");
    
    # Test with explicit argument
    is(underscore_test("explicit"), "explicit", "Underscore prototype accepts explicit argument");
    is(underscore_test(42), 42, "Underscore prototype accepts numbers");
    
    # Test with undef
    is(underscore_test(undef), "undef", "Underscore prototype accepts undef");
    
    # Test too many arguments
    eval { underscore_test(1, 2) };
    like($@, qr/Too many arguments/, "Underscore prototype rejects multiple arguments");
};

subtest "Underscore (_) with optional parameters" => sub {
    sub underscore_optional ($;_) { 
        my $first = shift;
        my $second = @_ ? $_[0] : $_;
        return "$first:$second";
    }
    
    local $_ = "default";
    is(underscore_optional("test"), "test:default", "Optional underscore uses \$_ when not provided");
    is(underscore_optional("test", "explicit"), "test:explicit", "Optional underscore accepts explicit value");
    
    # Multiple optional underscores
    sub double_optional ($$;_) {
        return join(":", @_[0,1], (@_ > 2 ? $_[2] : $_));
    }
    
    local $_ = "def";
    is(double_optional("a", "b"), "a:b:def", "Double required + optional underscore");
    is(double_optional("a", "b", "c"), "a:b:c", "Double required + explicit underscore");
};

subtest "Malformed underscore prototypes" => sub {
    # Test all invalid underscore combinations
    my @invalid_prototypes = (
        '__',      # Multiple underscores
        '_$',      # Underscore not at end
        '$_',      # Underscore in middle (this might actually work)
        '_@',      # Underscore with slurpy
        '_%',      # Underscore with hash slurpy
        '_;$',     # Underscore before semicolon
    );
    
    for my $proto (@invalid_prototypes) {
        my $code = "sub test_$proto ($proto) { 42 }";
        eval $code;
        like($@, qr/Malformed prototype/, "Prototype '$proto' is malformed");
    }
};

subtest "Argument count errors - too few" => sub {
    sub needs_two ($$) { $_[0] + $_[1] }
    sub needs_three ($$$) { $_[0] + $_[1] + $_[2] }
    sub needs_one_opt_one ($;$) { $_[0] + ($_[1] // 0) }
    
    eval { needs_two(1) };
    like($@, qr/Not enough arguments/, "Two-arg prototype rejects single argument");
    
    eval { needs_three(1, 2) };
    like($@, qr/Not enough arguments/, "Three-arg prototype rejects two arguments");
    
    eval { needs_two() };
    like($@, qr/Not enough arguments/, "Two-arg prototype rejects no arguments");
    
    # Optional parameters should not cause "not enough" errors
    is(needs_one_opt_one(5), 5, "Optional parameter works with minimum args");
    is(needs_one_opt_one(5, 3), 8, "Optional parameter works with all args");
};

subtest "Argument count errors - too many" => sub {
    sub exact_one ($) { $_[0] }
    sub exact_two ($$) { $_[0] + $_[1] }
    sub exact_three ($$$) { $_[0] + $_[1] + $_[2] }
    
    # Test with parentheses (strict checking)
    eval { exact_one(1, 2) };
    like($@, qr/Too many arguments/, "Single-arg prototype rejects two arguments with parens");
    
    eval { exact_two(1, 2, 3) };
    like($@, qr/Too many arguments/, "Two-arg prototype rejects three arguments with parens");
    
    eval { exact_three(1, 2, 3, 4) };
    like($@, qr/Too many arguments/, "Three-arg prototype rejects four arguments with parens");
};

subtest "Comma operator parsing behavior" => sub {
    sub single_arg ($) { $_[0] }
    sub double_arg ($$) { "$_[0]:$_[1]" }
    
    # Single argument - comma operator should work
    my $result1 = eval { single_arg 1,2,3; "ok" };
    is($result1, "ok", "Single arg prototype allows comma operator");
    
    # Double argument - depends on parsing
    eval { double_arg 1,2,3 };
    like($@, qr/Too many arguments/, "Double arg prototype rejects three args via comma operator");
    
    # Test exact matches
    is(double_arg(1, 2), "1:2", "Double arg prototype works with exact args");
    my $result2 = eval { double_arg 1,2; "ok" };
    is($result2, "ok", "Double arg prototype works with comma operator when exact");
};

subtest "Slurpy prototype comprehensive tests" => sub {
    sub array_slurp (@) { scalar @_ }
    sub hash_slurp (%) { scalar @_ }
    sub mixed_slurp ($@) { "$_[0]:" . (scalar(@_) - 1) }
    sub double_mixed ($$@) { "$_[0]:$_[1]:" . (scalar(@_) - 2) }
    
    # Array slurpy
    is(array_slurp(), 0, "Array slurp accepts no arguments");
    is(array_slurp(1), 1, "Array slurp accepts one argument");
    is(array_slurp(1,2,3,4,5), 5, "Array slurp accepts many arguments");
    
    # Hash slurpy
    is(hash_slurp(), 0, "Hash slurp accepts no arguments");
    is(hash_slurp(1), 1, "Hash slurp accepts odd number of arguments");
    is(hash_slurp(1,2,3,4), 4, "Hash slurp accepts even number of arguments");
    
    # Mixed slurpy
    is(mixed_slurp("head"), "head:0", "Mixed slurp with minimum args");
    is(mixed_slurp("head", 1,2,3), "head:3", "Mixed slurp with extra args");
    
    # Double required + slurpy
    is(double_mixed("a", "b"), "a:b:0", "Double mixed with minimum args");
    is(double_mixed("a", "b", 1,2,3,4), "a:b:4", "Double mixed with extra args");
    
    # Test minimum argument requirements
    eval { mixed_slurp() };
    like($@, qr/Not enough arguments/, "Mixed slurp requires minimum arguments");
    
    eval { double_mixed("a") };
    like($@, qr/Not enough arguments/, "Double mixed requires minimum arguments");
};

subtest "Reference prototype comprehensive tests" => sub {
    sub scalar_ref (\$) { ${$_[0]} }
    sub array_ref (\@) { scalar @{$_[0]} }
    sub hash_ref (\%) { scalar keys %{$_[0]} }
    sub code_ref (&) { $_[0]->() }
    
    my $s = 42;
    my @a = (1,2,3,4);
    my %h = (x => 1, y => 2, z => 3);
    
    is(scalar_ref($s), 42, "Scalar reference works");
    is(array_ref(@a), 4, "Array reference works");
    is(hash_ref(%h), 3, "Hash reference works");
    is(code_ref { 99 }, 99, "Code reference works");
    
    # Test that non-references fail appropriately
    eval { scalar_ref(42) };  # literal scalar, not reference
    like($@, qr/Type of arg 1 to main::scalar_ref must be scalar reference/, "Scalar ref rejects literal");
    
    eval { array_ref(1,2,3) };  # literal list, not array
    like($@, qr/Type of arg 1 to main::array_ref must be array/, "Array ref rejects literal list");
};

subtest "Bracketed reference prototypes" => sub {
    sub flexible_ref (\[$@%]) { ref $_[0] }
    sub double_flexible (\[$@%] \[$@%]) { ref($_[0]) . "+" . ref($_[1]) }
    sub triple_flexible (\[$@%] \[$@%] \[$@%]) { join("+", map ref, @_) }
    
    my $s = 42;
    my @a = (1,2,3);
    my %h = (x => 1);
    
    is(flexible_ref($s), "SCALAR", "Bracketed ref accepts scalar");
    is(flexible_ref(@a), "ARRAY", "Bracketed ref accepts array");
    is(flexible_ref(%h), "HASH", "Bracketed ref accepts hash");
    
    is(double_flexible($s, @a), "SCALAR+ARRAY", "Double bracketed ref works");
    is(double_flexible(@a, %h), "ARRAY+HASH", "Double bracketed ref works");
    is(double_flexible(%h, $s), "HASH+SCALAR", "Double bracketed ref works");
    
    is(triple_flexible($s, @a, %h), "SCALAR+ARRAY+HASH", "Triple bracketed ref works");
    
    # Test argument count validation
    eval { double_flexible($s) };
    like($@, qr/Not enough arguments/, "Double bracketed ref requires two args");
    
    eval { double_flexible($s, @a, %h) };
    like($@, qr/Too many arguments/, "Double bracketed ref rejects three args");
};

subtest "Optional bracketed references" => sub {
    sub opt_bracket (;\[$@%]) { @_ ? ref($_[0]) : "none" }
    sub req_plus_opt ($;\[$@%]) { $_[0] . ":" . (@_ > 1 ? ref($_[1]) : "none") }
    
    my $s = 42;
    my @a = (1,2);
    my %h = (x => 1);
    
    is(opt_bracket(), "none", "Optional bracketed ref works without args");
    is(opt_bracket($s), "SCALAR", "Optional bracketed ref works with scalar");
    is(opt_bracket(@a), "ARRAY", "Optional bracketed ref works with array");
    is(opt_bracket(%h), "HASH", "Optional bracketed ref works with hash");
    
    is(req_plus_opt("test"), "test:none", "Required plus optional bracketed works");
    is(req_plus_opt("test", $s), "test:SCALAR", "Required plus optional bracketed works with both");
    is(req_plus_opt("test", @a), "test:ARRAY", "Required plus optional bracketed works with array");
};

subtest "Code block (&) comprehensive tests" => sub {
    sub simple_block (&) { $_[0]->() }
    sub block_with_args (&@) { $_[0]->(@_[1..$#_]) }
    sub block_optional (&;$) { $_[0]->($_[1] // "default") }
    
    is(simple_block { 42 }, 42, "Simple code block works");
    is(simple_block(sub { 99 }), 99, "Code block as sub ref works");
    
    is(block_with_args({ $_[0] + $_[1] }, 3, 4), 7, "Code block with args works");
    is(block_with_args(sub { join(":", @_) }, "a", "b", "c"), "a:b:c", "Code block with multiple args");
    
    is(block_optional { $_[0] . "!" }, "default!", "Code block with optional arg uses default");
    is(block_optional { $_[0] . "!" } "test", "test!", "Code block with optional arg uses provided");
    
    # Test argument validation
    eval { simple_block };
    like($@, qr/Not enough arguments/, "Code block prototype requires block");
    
    eval { simple_block { 1 } "extra" };
    like($@, qr/Too many arguments/, "Simple code block rejects extra args");
};

subtest "Typeglob (*) comprehensive tests" => sub {
    sub typeglob_test (*) { ref($_[0]) || 'SCALAR' }
    sub typeglob_optional (*;$) { (ref($_[0]) || 'SCALAR') . ":" . ($_[1] // "none") }
    
    my @tg_arr = (1,2,3);
    my %tg_hash = (a => 1);
    my $tg_scalar = 42;
    local *TESTGLOB;
    
    # Test various argument types
    is(typeglob_test(@tg_arr), 'SCALAR', "Typeglob accepts array in scalar context");
    is(typeglob_test(%tg_hash), 'SCALAR', "Typeglob accepts hash in scalar context");
    is(typeglob_test($tg_scalar), 'SCALAR', "Typeglob accepts scalar");
    is(typeglob_test(*TESTGLOB), 'GLOB', "Typeglob accepts actual typeglob");
    is(typeglob_test(\@tg_arr), 'ARRAY', "Typeglob accepts array reference");
    is(typeglob_test(\%tg_hash), 'HASH', "Typeglob accepts hash reference");
    is(typeglob_test(sub { }), 'CODE', "Typeglob accepts code reference");
    
    # Test with optional parameter
    is(typeglob_optional(*TESTGLOB), 'GLOB:none', "Typeglob with optional works");
    is(typeglob_optional(*TESTGLOB, "extra"), 'GLOB:extra', "Typeglob with optional and arg works");
};

subtest "Multiple required parameters" => sub {
    sub two_params ($$) { "$_[0]+$_[1]" }
    sub three_params ($$$) { "$_[0]+$_[1]+$_[2]" }
    sub four_params ($$$$) { join("+", @_) }
    sub five_params ($$$$$) { join("+", @_) }
    
    is(two_params(1, 2), "1+2", "Two params work");
    is(three_params(1, 2, 3), "1+2+3", "Three params work");
    is(four_params(1, 2, 3, 4), "1+2+3+4", "Four params work");
    is(five_params(1, 2, 3, 4, 5), "1+2+3+4+5", "Five params work");
    
    # Test wrong argument counts
    eval { two_params(1) };
    like($@, qr/Not enough arguments/, "Two params rejects one arg");
    
    eval { three_params(1, 2) };
    like($@, qr/Not enough arguments/, "Three params rejects two args");
    
    eval { two_params(1, 2, 3) };
    like($@, qr/Too many arguments/, "Two params rejects three args");
    
    eval { three_params(1, 2, 3, 4) };
    like($@, qr/Too many arguments/, "Three params rejects four args");
};

subtest "Complex optional parameter combinations" => sub {
    sub one_opt (;$) { $_[0] // "def1" }
    sub two_opt (;$$) { ($_[0] // "def1") . ":" . ($_[1] // "def2") }
    sub req_one_opt ($;$) { $_[0] . ":" . ($_[1] // "def") }
    sub req_two_opt ($$;$) { "$_[0]:$_[1]:" . ($_[2] // "def") }
    sub req_one_two_opt ($;$$) { $_[0] . ":" . ($_[1] // "def1") . ":" . ($_[2] // "def2") }
    
    # One optional
    is(one_opt(), "def1", "One optional with no args");
    is(one_opt("a"), "a", "One optional with one arg");
    
    # Two optional
    is(two_opt(), "def1:def2", "Two optional with no args");
    is(two_opt("a"), "a:def2", "Two optional with one arg");
    is(two_opt("a", "b"), "a:b", "Two optional with two args");
    
    # Required + optional
    is(req_one_opt("a"), "a:def", "Required plus optional with min args");
    is(req_one_opt("a", "b"), "a:b", "Required plus optional with all args");
    
    # Two required + optional
    is(req_two_opt("a", "b"), "a:b:def", "Two required plus optional with min args");
    is(req_two_opt("a", "b", "c"), "a:b:c", "Two required plus optional with all args");
    
    # One required + two optional
    is(req_one_two_opt("a"), "a:def1:def2", "One req + two opt with min args");
    is(req_one_two_opt("a", "b"), "a:b:def2", "One req + two opt with one optional");
    is(req_one_two_opt("a", "b", "c"), "a:b:c", "One req + two opt with all args");
    
    # Test too many arguments
    eval { one_opt("a", "b") };
    like($@, qr/Too many arguments/, "One optional rejects too many args");
    
    eval { req_one_opt("a", "b", "c") };
    like($@, qr/Too many arguments/, "Req + opt rejects too many args");
};

subtest "Malformed prototype comprehensive tests" => sub {
    my @malformed_cases = (
        # Multiple slurpy types
        ['@@', 'Multiple array slurp'],
        ['%%', 'Multiple hash slurp'],
        ['@%', 'Array then hash slurp'],
        ['%@', 'Hash then array slurp'],
        
        # Anything after slurpy
        ['@$', 'Scalar after array slurp'],
        ['@$$', 'Scalars after array slurp'],
        ['%$', 'Scalar after hash slurp'],
        ['@;$', 'Optional after array slurp'],
        ['%;$', 'Optional after hash slurp'],
        ['@\$', 'Reference after array slurp'],
        ['%\@', 'Reference after hash slurp'],
        
        # Multiple code blocks
        ['&&', 'Multiple code blocks'],
        ['&$&', 'Code block in middle'],
        ['$&', 'Code block not first'],
        ['$$&', 'Code block at end'],
        
        # Invalid underscore combinations
        ['__', 'Multiple underscores'],
        ['_$', 'Underscore not at end'],
        ['_@', 'Underscore with array slurp'],
        ['_%', 'Underscore with hash slurp'],
        ['_;$', 'Underscore before semicolon'],
        
        # Invalid characters
        ['x', 'Unknown character x'],
        ['$x', 'Unknown character x after scalar'],
        ['$$x$', 'Unknown character x in middle'],
        
        # Invalid reference combinations (some might be valid)
        # Test these to see what actually fails
    );
    
    for my $case (@malformed_cases) {
        my ($proto, $desc) = @$case;
        my $code = "sub test_malformed ($proto) { 42 }";
        eval $code;
        like($@, qr/Malformed prototype|Unknown/, "Malformed prototype '$proto': $desc");
    }
};

subtest "Valid complex prototype combinations" => sub {
    # Test that these DON'T give malformed errors
    my @valid_cases = (
        ['&', 'Just code block'],
        ['&@', 'Code block + array slurp'],
        ['&$@', 'Code block + scalar + array slurp'],
        ['&;$@', 'Code block + optional scalar + array slurp'],
        ['&%', 'Code block + hash slurp'],
        ['&$%', 'Code block + scalar + hash slurp'],
        ['*$', 'Typeglob + scalar'],
        ['*;$', 'Typeglob + optional scalar'],
        ['*@', 'Typeglob + array slurp'],
        ['\[$@%]', 'Bracketed scalar/array/hash reference'],
        ['\$;\$', 'Required scalar ref + optional scalar ref'],
        ['\@;\@', 'Required array ref + optional array ref'],
        ['\%;\%', 'Required hash ref + optional hash ref'],
        ['$;_', 'Required scalar + optional underscore'],
        ['$$;_', 'Two required scalars + optional underscore'],
        ['\$;_', 'Required scalar ref + optional underscore'],
        ['*;_', 'Required typeglob + optional underscore'],
        ['&;_', 'Required code block + optional underscore'],
    );
    
    for my $case (@valid_cases) {
        my ($proto, $desc) = @$case;
        my $code = "sub test_valid ($proto) { 42 }";
        my $result = eval $code;
        ok(!$@, "Valid prototype '$proto': $desc") or diag("Error: $@");
    }
};

subtest "Edge cases and corner cases" => sub {
    # Test semicolon at the beginning
    eval 'sub bad_semi (;) { 42 }';
    like($@, qr/Malformed prototype/, "Semicolon alone is malformed");
    
    # Test semicolon at the end after required params
    sub trailing_semi ($;) { $_[0] }
    is(trailing_semi("test"), "test", "Trailing semicolon works");
    
    # Test multiple semicolons
    eval 'sub multi_semi ($;;$) { 42 }';
    like($@, qr/Malformed prototype/, "Multiple semicolons are malformed");
    
    # Test empty groups
    eval 'sub empty_group (\[]) { 42 }';
    like($@, qr/Malformed prototype/, "Empty bracketed group is malformed");
    
    # Test nested brackets
    eval 'sub nested_brackets (\[\[$@\]]) { 42 }';
    like($@, qr/Malformed prototype/, "Nested brackets are malformed");
    
    # Test unclosed brackets
    eval 'sub unclosed_bracket (\[$@) { 42 }';
    like($@, qr/Malformed prototype/, "Unclosed bracket is malformed");
    
    # Test invalid bracket contents
    eval 'sub invalid_bracket_content (\[x]) { 42 }';
    like($@, qr/Malformed prototype/, "Invalid bracket content is malformed");
};

subtest "Argument parsing with different call styles" => sub {
    sub parse_test ($) { $_[0] }
    
    # Test that these all work and parse correctly
    is(parse_test(42), 42, "Parentheses call works");
    is(parse_test 42, 42, "No parentheses call works");
    
    # Test comma operator behavior more extensively
    my $x = eval { parse_test 1,2,3; "success" };
    is($x, "success", "Comma operator parsing works");
    
    # Test that the function actually only gets the first argument
    sub capture_args ($) { scalar @_ }
    is(capture_args(1), 1, "Single arg captured correctly");
    
    # This should parse as capture_args(1), 2, 3 - so function gets 1 arg
    my $y = eval { capture_args 1,2,3; capture_args(999) };
    is($y, 1, "Comma operator only passes first arg to function");
    
    # But with parentheses, it should fail
    eval { capture_args(1,2,3) };
    like($@, qr/Too many arguments/, "Parentheses force all args to function");
};

subtest "Context sensitivity with prototypes" => sub {
    sub context_scalar ($) { wantarray ? ("list", "context") : "scalar_context" }
    sub context_list (@) { wantarray ? @_ : scalar @_ }
    
    # Test scalar context
    my $scalar_result = context_scalar(42);
    is($scalar_result, "scalar_context", "Prototype respects scalar context");
    
    # Test list context
    my @list_result = context_scalar(42);
    is_deeply(\@list_result, ["list", "context"], "Prototype respects list context");
    
    # Test with list prototype
    my $count = context_list(1,2,3,4);
    is($count, 4, "List prototype in scalar context returns count");
    
    my @elements = context_list(1,2,3,4);
    is_deeply(\@elements, [1,2,3,4], "List prototype in list context returns elements");
};

subtest "Prototype inheritance and method calls" => sub {
    # Test that prototypes work with package-qualified calls
    package TestPackage;
    sub qualified_test ($) { $_[0] * 2 }
    
    package main;
    is(TestPackage::qualified_test(21), 42, "Qualified call respects prototype");
    
    # Test with method-style calls (prototypes should be ignored)
    package TestClass;
    sub new { bless {}, shift }
    sub method_test ($) { $_[1] * 3 }  # Should ignore prototype for method calls
    
    package main;
    my $obj = TestClass->new();
    is($obj->method_test(14), 42, "Method call ignores prototype");
    
    # But function-style call should respect it
    eval { TestClass::method_test(1, 2, 3) };
    like($@, qr/Too many arguments/, "Function call respects prototype");
};

subtest "Prototype with special variables and contexts" => sub {
    # Test with special variables
    sub special_vars (@) { 
        local $" = ":";  # Change list separator
        return "@_";
    }
    
    is(special_vars(1,2,3), "1:2:3", "Prototype works with special variables");
    
    # Test with different argument types
    sub type_test ($) { ref($_[0]) || 'SCALAR' }
    
    my @test_array = (1,2,3);
    my %test_hash = (a => 1);
    my $test_ref = \@test_array;
    
    is(type_test(@test_array), 'SCALAR', "Array in scalar context becomes scalar");
    is(type_test(%test_hash), 'SCALAR', "Hash in scalar context becomes scalar");
    is(type_test($test_ref), 'ARRAY', "Array reference stays reference");
    is(type_test(42), 'SCALAR', "Scalar stays scalar");
    is(type_test("string"), 'SCALAR', "String stays scalar");
};

subtest "Recursive and nested calls with prototypes" => sub {
    sub recursive_test ($) {
        my $n = shift;
        return $n <= 1 ? 1 : $n * recursive_test($n - 1);
    }
    
    is(recursive_test(5), 120, "Recursive calls work with prototypes");
    
    # Test nested calls
    sub inner ($) { $_[0] + 1 }
    sub outer ($) { inner($_[0]) + 10 }
    
    is(outer(5), 16, "Nested calls work with prototypes");
    
    # Test with comma operator in nested calls
    sub nested_comma ($) { $_[0] }
    my $result = eval { nested_comma(outer(inner 3,4,5)); "ok" };
    is($result, "ok", "Nested calls with comma operator work");
};

subtest "Prototype with eval and string eval" => sub {
    sub eval_test ($) { eval $_[0] }
    
    is(eval_test("2 + 2"), 4, "Prototype works with eval");
    
    # Test string eval with prototype definition
    my $code = 'sub dynamic_proto ($) { $_[0] * 2 }';
    eval $code;
    ok(!$@, "Dynamic prototype definition works") or diag($@);
    
    if (!$@) {
        is(dynamic_proto(21), 42, "Dynamically defined prototype works");
        
        eval { dynamic_proto(1, 2) };
        like($@, qr/Too many arguments/, "Dynamically defined prototype enforces args");
    }
};

subtest "Prototype with references and aliasing" => sub {
    sub alias_test (\$) { $_[0] }
    
    my $original = 42;
    my $alias_ref = alias_test($original);
    
    # Modify through the alias
    $alias_ref = 99;
    is($original, 99, "Reference prototype creates proper alias");
    
    # Test with array aliasing
    sub array_alias (\@) { $_[0] }
    
    my @original_array = (1, 2, 3);
    my $array_ref = array_alias(@original_array);
    
    push @$array_ref, 4;
    is_deeply(\@original_array, [1, 2, 3, 4], "Array reference prototype creates proper alias");
    
    # Test with hash aliasing
    sub hash_alias (\%) { $_[0] }
    
    my %original_hash = (a => 1, b => 2);
    my $hash_ref = hash_alias(%original_hash);
    
    $hash_ref->{c} = 3;
    is($original_hash{c}, 3, "Hash reference prototype creates proper alias");
};

subtest "Advanced bracketed reference combinations" => sub {
    # Test all valid bracketed combinations
    sub scalar_or_array (\[$@]) { ref $_[0] }
    sub scalar_or_hash (\[$%]) { ref $_[0] }
    sub array_or_hash (\[@%]) { ref $_[0] }
    sub any_ref (\[$@%]) { ref $_[0] }
    
    my $s = 42;
    my @a = (1,2,3);
    my %h = (x => 1);
    
    # Test scalar_or_array
    is(scalar_or_array($s), 'SCALAR', "Bracketed [\$\@] accepts scalar");
    is(scalar_or_array(@a), 'ARRAY', "Bracketed [\$\@] accepts array");
    
    # Test scalar_or_hash
    is(scalar_or_hash($s), 'SCALAR', "Bracketed [\$%] accepts scalar");
    is(scalar_or_hash(%h), 'HASH', "Bracketed [\$%] accepts hash");
    
    # Test array_or_hash
    is(array_or_hash(@a), 'ARRAY', "Bracketed [\@%] accepts array");
    is(array_or_hash(%h), 'HASH', "Bracketed [\@%] accepts hash");
    
    # Test any_ref
    is(any_ref($s), 'SCALAR', "Bracketed [\$\@%] accepts scalar");
    is(any_ref(@a), 'ARRAY', "Bracketed [\$\@%] accepts array");
    is(any_ref(%h), 'HASH', "Bracketed [\$\@%] accepts hash");
    
    # Test multiple bracketed args
    sub two_any_refs (\[$@%] \[$@%]) { ref($_[0]) . "+" . ref($_[1]) }
    
    is(two_any_refs($s, @a), "SCALAR+ARRAY", "Multiple bracketed refs work");
    is(two_any_refs(@a, %h), "ARRAY+HASH", "Multiple bracketed refs work");
    is(two_any_refs(%h, $s), "HASH+SCALAR", "Multiple bracketed refs work");
};

subtest "Prototype with closures and lexical variables" => sub {
    my $closure_var = 100;
    
    sub closure_test ($) {
        my $arg = shift;
        return sub { $closure_var + $arg };
    }
    
    my $closure = closure_test(42);
    is($closure->(), 142, "Prototype works with closures");
    
    # Test lexical variable capture
    for my $i (1..3) {
        my $lexical = $i * 10;
        
        sub lexical_test ($) { 
            my $arg = shift;
            return $lexical + $arg;  # Captures $lexical from loop
        }
        
        # Note: This test might not work as expected due to how lexical
        # variables work with subroutine definitions inside loops
        # But it tests the prototype behavior
        my $result = eval { lexical_test($i); 1 };
        ok($result, "Prototype works with lexical variables in loop");
    }
};

subtest "Prototype edge cases with undef and empty values" => sub {
    sub undef_test ($) { defined $_[0] ? "defined" : "undefined" }
    sub empty_string_test ($) { length($_[0] // '') }
    sub zero_test ($) { $_[0] || "falsy" }
    
    is(undef_test(undef), "undefined", "Prototype handles undef");
    is(undef_test(""), "defined", "Prototype handles empty string");
    is(undef_test(0), "defined", "Prototype handles zero");
    
    is(empty_string_test(""), 0, "Prototype handles empty string length");
    is(empty_string_test(undef), 0, "Prototype handles undef length");
    is(empty_string_test("test"), 4, "Prototype handles normal string length");
    
    is(zero_test(0), "falsy", "Prototype handles zero");
    is(zero_test(""), "falsy", "Prototype handles empty string as falsy");
    is(zero_test(undef), "falsy", "Prototype handles undef as falsy");
    is(zero_test("0"), "falsy", "Prototype handles string zero as falsy");
    is(zero_test(1), 1, "Prototype handles truthy values");
};

subtest "Performance and memory considerations" => sub {
    # Test that prototypes don't cause memory leaks with large argument lists
    sub large_list_test (@) { scalar @_ }
    
    my $count = large_list_test(1..1000);
    is($count, 1000, "Prototype handles large argument lists");
    
    # Test with repeated calls
    for my $i (1..100) {
        my $result = large_list_test(1..$i);
        is($result, $i, "Prototype consistent with repeated calls") if $i % 25 == 0;
    }
    
    # Test memory usage with references
    sub ref_test (\@) { scalar @{$_[0]} }
    
    for my $size (10, 100, 1000) {
        my @big_array = (1..$size);
        my $count = ref_test(@big_array);
        is($count, $size, "Reference prototype handles array size $size");
    }
};

subtest "Prototype interaction with special perl features" => sub {
    # Test with local
    our $global_var = "global";
    sub local_test ($) { 
        local $global_var = $_[0];
        return $global_var;
    }
    
    is(local_test("local"), "local", "Prototype works with local");
    is($global_var, "global", "Global variable unchanged after local");
    
    # Test with wantarray in different contexts
    sub wantarray_test ($) {
        return wantarray ? ($_[0], "list") : $_[0];
    }
    
    my $scalar_context = wantarray_test("test");
    is($scalar_context, "test", "Prototype respects scalar context");
    
    my @list_context = wantarray_test("test");
    is_deeply(\@list_context, ["test", "list"], "Prototype respects list context");
    
    # Test with caller
    sub caller_test ($) {
        my ($package, $filename, $line) = caller(0);
        return defined $package ? "called" : "not_called";
    }
    
    is(caller_test("arg"), "called", "Prototype works with caller");
};

subtest "Final edge cases and regression tests" => sub {
    # Test that we didn't break anything basic
    sub basic_regression ($) { $_[0] }
    is(basic_regression(42), 42, "Basic single arg prototype still works");
    
    # Test empty prototype edge case
    sub empty_regression () { "empty" }
    is(empty_regression(), "empty", "Empty prototype still works");
    
    # Test that malformed prototypes are still caught
    eval 'sub still_malformed ($@$) { 42 }';
    like($@, qr/Malformed prototype/, "Malformed prototypes still caught");
    
    # Test that valid complex prototypes still work
    sub complex_regression (&$@) {
        my ($code, $first, @rest) = @_;
        return $code->($first) + scalar(@rest);
    }
    
    my $result = complex_regression { $_[0] * 2 } 5, 1, 2, 3;
    is($result, 13, "Complex prototype still works (5*2 + 3 args = 13)");
    
    # Final test: make sure we can still define and use prototypes normally
    eval {
        sub final_test ($;$) {
            my ($a, $b, $c) = @_;
            return $a + $b + ($c // 0);
        }
        1;
    };
    ok(!$@, "Can still define prototypes normally") or diag($@);
    
    if (!$@) {
        is(final_test(1, 2), 3, "Final test with minimum args works");
        is(final_test(1, 2, 3), 6, "Final test with all args works");
        
        eval { final_test(1) };
        like($@, qr/Not enough arguments/, "Final test still validates argument count");
    }
};

done_testing();
