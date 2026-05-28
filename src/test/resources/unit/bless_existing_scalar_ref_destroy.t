use strict;
use warnings;
use Test::More;
use Scalar::Util qw(refaddr);

our @destroyed;

{
    package BlessExistingScalarRef;
    sub DESTROY { push @main::destroyed, Scalar::Util::refaddr($_[0]) }
}

sub make_two_step {
    my $obj = \(my $value);
    bless $obj, 'BlessExistingScalarRef';
    return $obj;
}

my $obj = make_two_step();
my $addr = refaddr($obj);

is_deeply(\@destroyed, [], 'two-step blessed scalar ref is not destroyed before caller receives it');

undef $obj;

is_deeply(\@destroyed, [$addr], 'two-step blessed scalar ref is destroyed when caller drops it');

our $global = \(my $global_value);
bless $global, 'BlessExistingScalarRef';
my $global_addr = refaddr($global);

is_deeply(\@destroyed, [$addr], 'global two-step blessed scalar ref survives bless statement');

undef $global;

is_deeply(\@destroyed, [$addr, $global_addr], 'global two-step blessed scalar ref is destroyed on undef');

done_testing();
