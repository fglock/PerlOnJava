#!/usr/bin/env perl

use strict;
use warnings;
use Scalar::Util ();
use Test::More tests => 3;

{
    package BlessedCodeIsa;

    sub new {
        my $class = shift;
        return bless sub { 1 }, $class;
    }
}

my $code = BlessedCodeIsa->new;

ok(Scalar::Util::blessed($code), 'anonymous sub can be blessed');
ok($code->isa('CODE'), 'blessed CODE ref reports CODE through method isa');
ok(UNIVERSAL::isa($code, 'CODE'), 'blessed CODE ref reports CODE through UNIVERSAL::isa');
