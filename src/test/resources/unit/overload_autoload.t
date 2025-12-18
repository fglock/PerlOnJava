use strict;
use warnings;
use Test::More tests => 3;

# Test that overload methods (which start with '(') do not trigger AUTOLOAD
# This is a regression test for a bug where accessing a blessed hash element
# would incorrectly trigger AUTOLOAD with an empty method name when the class
# used 'use overload' and had an AUTOLOAD sub defined.

subtest 'overload with AUTOLOAD - basic hash access' => sub {
    plan tests => 3;
    
    package TestOverloadAutoload1;
    use overload;
    
    our $autoload_called = 0;
    our $autoload_name = '';
    
    sub AUTOLOAD {
        our $AUTOLOAD;
        $autoload_called = 1;
        $autoload_name = $AUTOLOAD // '';
    }
    
    sub new {
        my $class = shift;
        return bless {}, $class;
    }
    
    package main;
    
    my $obj = TestOverloadAutoload1->new;
    ok(defined $obj, 'Object created');
    
    # This should NOT trigger AUTOLOAD
    $obj->{foo} = 1;
    
    is($TestOverloadAutoload1::autoload_called, 0, 'AUTOLOAD was not called for hash access');
    is($obj->{foo}, 1, 'Hash element was set correctly');
};

subtest 'overload with AUTOLOAD - nested hash access' => sub {
    plan tests => 3;
    
    package TestOverloadAutoload2;
    use overload;
    
    our $autoload_called = 0;
    
    sub AUTOLOAD {
        our $AUTOLOAD;
        $autoload_called = 1;
    }
    
    sub new {
        my $class = shift;
        my $self = bless {}, $class;
        $self->{OPTIONS} = { };
        return $self;
    }
    
    package main;
    
    $TestOverloadAutoload2::autoload_called = 0;
    my $obj = TestOverloadAutoload2->new;
    ok(defined $obj, 'Object with nested hash created');
    is($TestOverloadAutoload2::autoload_called, 0, 'AUTOLOAD was not called during construction');
    is(ref($obj->{OPTIONS}), 'HASH', 'Nested hash was created correctly');
};

subtest 'AUTOLOAD still works for actual missing methods' => sub {
    plan tests => 3;
    
    package TestOverloadAutoload3;
    use overload;
    
    our $autoload_called = 0;
    our $autoload_name = '';
    
    sub AUTOLOAD {
        our $AUTOLOAD;
        $autoload_called = 1;
        $autoload_name = $AUTOLOAD // '';
        return 'autoloaded';
    }
    
    sub new {
        my $class = shift;
        return bless {}, $class;
    }
    
    package main;
    
    $TestOverloadAutoload3::autoload_called = 0;
    my $obj = TestOverloadAutoload3->new;
    
    # This SHOULD trigger AUTOLOAD
    my $result = $obj->missing_method();
    
    is($TestOverloadAutoload3::autoload_called, 1, 'AUTOLOAD was called for missing method');
    is($TestOverloadAutoload3::autoload_name, 'TestOverloadAutoload3::missing_method', 'AUTOLOAD received correct method name');
    is($result, 'autoloaded', 'AUTOLOAD returned correct value');
};

1;
