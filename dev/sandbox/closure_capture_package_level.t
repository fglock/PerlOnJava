use strict;
use warnings;
use Test::More;

# Test: Closures should capture lexical variables assigned at package level
#
# This tests the basic pattern used by Test2::API:
#   my $STDOUT = clone_io(\*STDOUT);
#   sub test2_stdout { $STDOUT ||= clone_io(\*STDOUT) }

# Test 1: Simple string assignment
{
    package Test1;
    
    my $VAR = "test value";
    sub get_var { return $VAR; }
    
    package main;
    is(Test1::get_var(), "test value", "Closure captures string at package level");
}

# Test 2: Assignment from function call
{
    package Test2;
    
    sub make_value { return "from function"; }
    my $VAR = make_value();
    sub get_var { return $VAR; }
    
    package main;
    is(Test2::get_var(), "from function", "Closure captures function return value");
}

# Test 3: Filehandle reference
{
    package Test3;
    
    my $FH = \*STDOUT;
    sub get_fh { return $FH; }
    sub is_fh_defined { return defined($FH) ? "yes" : "no"; }
    
    package main;
    is(Test3::is_fh_defined(), "yes", "Filehandle ref is defined in closure");
    ok(defined(Test3::get_fh()), "Filehandle ref can be retrieved");
}

# Test 4: Cloned filehandle (simulates Test2::Util::clone_io)
{
    package Test4;
    
    sub clone_fh {
        my ($fh) = @_;
        my $fileno = fileno($fh);
        open(my $out, ">&$fileno") or die "Can't dup: $!";
        return $out;
    }
    
    my $CLONED = clone_fh(\*STDOUT);
    sub get_cloned { return $CLONED; }
    sub is_cloned_defined { return defined($CLONED) ? "yes" : "no"; }
    
    package main;
    is(Test4::is_cloned_defined(), "yes", "Cloned filehandle is defined in closure");
    ok(defined(Test4::get_cloned()), "Cloned filehandle can be retrieved");
}

# Test 5: The ||= pattern (exactly like Test2::API::test2_stdout)
{
    package Test5;
    
    sub make_value { return "initial"; }
    my $VAR = make_value();
    
    # This pattern fails if closure doesn't capture initial value
    sub get_with_fallback { $VAR ||= "fallback" }
    
    package main;
    my $result = Test5::get_with_fallback();
    is($result, "initial", "Closure with ||= sees initial value (not fallback)");
}

# Test 6: Multiple lexicals
{
    package Test6;
    
    my $VAR1 = "first";
    my $VAR2 = "second";
    
    sub get_var1 { return $VAR1; }
    sub get_var2 { return $VAR2; }
    
    package main;
    is(Test6::get_var1(), "first", "First variable captured");
    is(Test6::get_var2(), "second", "Second variable captured");
}

# Test 7: Variable is defined check
{
    package Test7;
    
    my $VAR = "value";
    sub is_defined { return defined($VAR) ? "yes" : "no"; }
    
    package main;
    is(Test7::is_defined(), "yes", "Variable is defined inside closure");
}

done_testing();
