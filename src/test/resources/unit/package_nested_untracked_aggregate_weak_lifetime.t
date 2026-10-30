use strict;
use warnings;
use Scalar::Util qw(isweak weaken);
use Test::More tests => 4;

our @package_root;
my $weak;

{
    my $target = [];
    @package_root = ($target, $target);
    $weak = $target;
    weaken($weak);
}

ok(isweak($weak), 'the observer is weak after first weaken');
ok(defined($weak), 'package array elements keep the target alive after lexical exit');

$package_root[0] = undef;
ok(defined($weak), 'one remaining package element keeps the target alive');

$package_root[1] = undef;
ok(!defined($weak), 'the final package element release clears the weak observer');
