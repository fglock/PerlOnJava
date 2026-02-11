use strict;
use warnings;
use Test::More;

# Test 1: $_ sharing (read)
{
    $_ = 42;
    my $getter = eval 'sub { $_ }';
    is($getter->(), 42, "Interpreted code reads global \$_");
}

# Test 2: $_ sharing (write)
{
    my $setter = eval 'sub { $_ = $_[0] }';
    $setter->(99);
    is($_, 99, "Interpreted code modifies global \$_");
}

# Test 3: $@ sharing (eval errors)
{
    eval { eval 'die "test error"' };
    like($@, qr/test error/, "Interpreted die sets \$@");
}

# Test 4: Package variables (read)
{
    our $TestVar = 123;
    my $getter = eval 'sub { $main::TestVar }';
    is($getter->(), 123, "Interpreted code reads package variable");
}

# Test 5: Package variables (write)
{
    our $TestVar2 = 100;
    my $setter = eval 'sub { $main::TestVar2 = $_[0] }';
    $setter->(456);
    is($TestVar2, 456, "Interpreted code modifies package variable");
}

# Test 6: Arrays
{
    our @arr = (1, 2, 3);
    my $getter = eval 'sub { scalar @arr }';
    is($getter->(), 3, "Interpreted code reads global array");
}

# Test 7: Hashes
{
    our %hash = (a => 1, b => 2);
    my $getter = eval 'sub { $hash{a} }';
    is($getter->(), 1, "Interpreted code reads global hash");
}

done_testing();
