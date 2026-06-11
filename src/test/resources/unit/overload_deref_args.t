use strict;
use warnings;
use Test::More;
use IO::File;

{
    package OverloadDerefArgs::Scalar;
    our @seen;
    use overload '${}' => sub {
        push @seen, [@_];
        my $value = 'scalar';
        return \$value;
    };
    sub new { bless {}, shift }
}

{
    package OverloadDerefArgs::Array;
    our @seen;
    use overload '@{}' => sub {
        push @seen, [@_];
        return [ 'array' ];
    };
    sub new { bless {}, shift }
}

{
    package OverloadDerefArgs::Hash;
    our @seen;
    use overload '%{}' => sub {
        push @seen, [@_];
        return { hash => 1 };
    };
    sub new { bless {}, shift }
}

{
    package OverloadDerefArgs::Code;
    our @seen;
    use overload '&{}' => sub {
        push @seen, [@_];
        return sub { 'code' };
    };
    sub new { bless {}, shift }
}

subtest 'scalar dereference overload arguments' => sub {
    my $obj = OverloadDerefArgs::Scalar->new;
    is($$obj, 'scalar', 'scalar dereference overload returns referenced value');
    is(scalar @{ $OverloadDerefArgs::Scalar::seen[0] }, 3, 'three overload arguments');
    ok(!defined $OverloadDerefArgs::Scalar::seen[0][1], 'second argument is undef');
    ok(!$OverloadDerefArgs::Scalar::seen[0][2], 'swap argument is false');
};

subtest 'array dereference overload arguments' => sub {
    my $obj = OverloadDerefArgs::Array->new;
    is($obj->[0], 'array', 'array dereference overload returns array reference');
    is(scalar @{ $OverloadDerefArgs::Array::seen[0] }, 3, 'three overload arguments');
    ok(!defined $OverloadDerefArgs::Array::seen[0][1], 'second argument is undef');
    ok(!$OverloadDerefArgs::Array::seen[0][2], 'swap argument is false');
};

subtest 'hash dereference overload arguments' => sub {
    my $obj = OverloadDerefArgs::Hash->new;
    is($obj->{hash}, 1, 'hash dereference overload returns hash reference');
    is(scalar @{ $OverloadDerefArgs::Hash::seen[0] }, 3, 'three overload arguments');
    ok(!defined $OverloadDerefArgs::Hash::seen[0][1], 'second argument is undef');
    ok(!$OverloadDerefArgs::Hash::seen[0][2], 'swap argument is false');
};

subtest 'code dereference overload arguments' => sub {
    my $obj = OverloadDerefArgs::Code->new;
    is($obj->(), 'code', 'code dereference overload returns code reference');
    is(scalar @{ $OverloadDerefArgs::Code::seen[0] }, 3, 'three overload arguments');
    ok(!defined $OverloadDerefArgs::Code::seen[0][1], 'second argument is undef');
    ok(!$OverloadDerefArgs::Code::seen[0][2], 'swap argument is false');
};

subtest 'IO::File new_tmpfile works as a function' => sub {
    my $fh = IO::File::new_tmpfile();
    isa_ok($fh, 'IO::File');
};

done_testing();
