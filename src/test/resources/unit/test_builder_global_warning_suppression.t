use strict;
use warnings;

use Scalar::Util qw(looks_like_number);
use Test::Builder;

$^W = 1;

my $builder = Test::Builder->new;
$builder->plan(tests => 4);

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    $builder->ok(1, 'first named assertion');
    $builder->ok(1, 'second named assertion');
}

my $current = $builder->current_test;
$builder->ok(@warnings == 0);
$builder->ok(looks_like_number($current) && $current == 2);
