use strict;
use warnings;
use Test::More;

BEGIN { $INC{'Moose/Autobox.pm'} = __FILE__ }

BEGIN {
    package Moose::Autobox;
    require autobox;
    our @ISA = ('autobox');
    sub import {
        shift->SUPER::import(
            DEFAULT => 'Moose::Autobox::',
            UNDEF => 'Moose::Autobox::Undef');
    }
}

package Moose::Autobox::SCALAR;
sub native_kind { 'scalar:' . $_[0] }

package Moose::Autobox::ARRAY;
sub item_count { scalar @{$_[0]} }

package Moose::Autobox::HASH;
sub key_count { scalar keys %{$_[0]} }

package Moose::Autobox::CODE;
use Moose::Autobox;
sub u {
    my $f = shift;
    sub { $f->($f, @_) };
}
sub y {
    my $f = shift;
    (sub { my $h = shift; sub { $f->(($h->u)->())->(@_) } }->u)->();
}

package Moose::Autobox::Undef;
sub native_kind { 'undef' }

package main;
use Moose::Autobox;

is('value'->native_kind, 'scalar:value',
    'Moose::Autobox default mapping dispatches native scalar methods');
is([1, 2, 3]->item_count, 3,
    'Moose::Autobox default mapping dispatches array-reference methods');
is({left => 1, right => 2}->key_count, 2,
    'Moose::Autobox default mapping dispatches hash-reference methods');
is(undef->native_kind, 'undef',
    'Moose::Autobox explicit UNDEF mapping dispatches methods');

my $fact_y = sub {
    my $f = shift;
    sub {
        my $n = shift;
        return 1 if $n < 2;
        return $n * $f->($n - 1);
    };
}->y;
is($fact_y->(10), 3628800,
    'Moose::Autobox CODE y method returns an ordinary callable');

my $fact_u = sub {
    my $f = shift;
    sub {
        my $n = shift;
        return 1 if $n < 2;
        return $n * ($f->($f))->($n - 1);
    };
}->u;
is($fact_u->()->(10), 3628800,
    'Moose::Autobox CODE u method preserves recursive coderef calls');

{
    no warnings 'once';
    *fact3 = sub {
        my $f = shift;
        sub {
            my $n = shift;
            return 1 if $n < 2;
            return $n * ($f->($f))->($n - 1);
        };
    }->u;
}
is(fact3()->(10), 3628800,
    'glob-installed Moose::Autobox CODE u results remain callable');

{
    no Moose::Autobox qw(SCALAR);
    my $result = eval { 'disabled'->native_kind };
    like($@, qr/Can't (?:call method|locate object method)/,
        'no Moose::Autobox disables the selected lexical mapping');
}
is('restored'->native_kind, 'scalar:restored',
    'outer Moose::Autobox mappings return after nested no');

done_testing();
