use Scalar::Util qw(weaken);
use Test::More tests => 3;

our ($weak, $destroyed);

{
    package LvalueLocalReturn::Object;
    sub DESTROY { ++$main::destroyed }
}

sub return_local :lvalue {
    my $value = bless {}, 'LvalueLocalReturn::Object';
    $weak = $value;
    weaken($weak);
    $value;
}

my $returned = return_local();
ok($weak, 'callee-local lvalue return remains live in caller');
is($destroyed // 0, 0, 'object is not destroyed while caller owns the lvalue');

undef $returned;
ok(!defined($weak) && $destroyed == 1,
    'weak observer clears and DESTROY runs after the caller releases the lvalue');
