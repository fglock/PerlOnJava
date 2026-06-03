#!/usr/bin/perl
use strict;
use warnings;
use Test::More tests => 13;

{
    package TiedLvalueSubReturnTie;

    sub TIESCALAR {
        my ($class, $get, $set) = @_;
        bless [$get, $set], $class;
    }

    sub FETCH {
        my ($self) = @_;
        return $self->[0]->();
    }

    sub STORE {
        my ($self, $value) = @_;
        return $self->[1]->($value);
    }
}

my $stored;

sub tied_lvalue :lvalue {
    tie(my $value, 'TiedLvalueSubReturnTie',
        sub { $stored },
        sub { $stored = $_[0] });
    $value;
}

tied_lvalue() = 10;
is($stored, 10, 'assignment to tied lvalue sub result calls STORE');
is(tied_lvalue(), 10, 'ordinary read from tied lvalue sub result calls FETCH');

tied_lvalue() *= 2;
is($stored, 20, 'compound multiplication stores through tied lvalue sub result');

tied_lvalue() += 3;
is($stored, 23, 'compound addition stores through tied lvalue sub result');

tied_lvalue() .= 'ski';
is($stored, '23ski', 'compound concatenation stores through tied lvalue sub result');

is(tied_lvalue() .= 'doo', '23skidoo', 'compound assignment returns stored value');
is($stored, '23skidoo', 'compound assignment result remains stored');

$stored = undef;
tied_lvalue() ||= 'beta';
is($stored, 'beta', 'logical ||= stores through tied lvalue sub result when false');

tied_lvalue() ||= 'gamma';
is($stored, 'beta', 'logical ||= skips STORE when tied lvalue sub result is true');

$stored = '';
tied_lvalue() &&= 'delta';
is($stored, '', 'logical &&= skips STORE when tied lvalue sub result is false');

$stored = 'true';
tied_lvalue() &&= 'delta';
is($stored, 'delta', 'logical &&= stores through tied lvalue sub result when true');

$stored = undef;
tied_lvalue() //= 'defined';
is($stored, 'defined', 'defined-or //= stores through tied lvalue sub result when undef');

tied_lvalue() //= 'again';
is($stored, 'defined', 'defined-or //= skips STORE when tied lvalue sub result is defined');
