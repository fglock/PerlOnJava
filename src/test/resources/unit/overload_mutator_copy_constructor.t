use strict;
use warnings;
use Test::More tests => 7;

{
    package Local::MutableNumber;

    use overload
        '+=' => sub {
            $_[0]{value} += $_[1];
            return $_[0];
        },
        '=' => sub {
            $main::copy_calls++;
            $main::copy_second = $_[1];
            $main::copy_third = $_[2];
            return bless { %{ $_[0] } }, ref $_[0];
        };
}

our ($copy_calls, $copy_second, $copy_third) = (0);
my $original = bless { value => 10 }, 'Local::MutableNumber';
my $alias = $original;

$original += 5;

is($original->{value}, 15, 'mutating overload updates the lvalue');
is($alias->{value}, 10, 'copy constructor preserves an aliased object');
is($copy_calls, 1, 'copy constructor is called once');
ok(!defined $copy_second, 'copy constructor second argument is undef');
is($copy_third, '', 'copy constructor third argument is empty string');

{
    package Local::NonMutatingNumber;
    use overload
        '+' => sub {
            return bless { value => $_[0]{value} + $_[1] }, ref $_[0];
        },
        '=' => sub {
            $main::fallback_copy_calls++;
            return bless { %{ $_[0] } }, ref $_[0];
        },
        fallback => 1;
}

our $fallback_copy_calls = 0;
my $fallback = bless { value => 4 }, 'Local::NonMutatingNumber';
$fallback += 3;
is($fallback->{value}, 7, 'compound assignment can be synthesized from base overload');
is($fallback_copy_calls, 0, 'base overload synthesis does not call copy constructor');
