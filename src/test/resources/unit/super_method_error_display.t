use strict;
use warnings;
use utf8;
use Test::More tests => 4;

{
    package Audit::Slosh;
    sub rip { shift->SUPER::rip }
}

eval { Audit::Slosh->rip };
like($@, qr/^Can't locate object method "rip"/, 'ASCII SUPER error renders terminal method');
unlike($@, qr/Audit::Slosh::SUPER::rip/, 'ASCII lookup qualification is not rendered');

{
    package ᔅᓗsḨ;
    sub 맆 { shift->SUPER::맆 }
}

eval { ᔅᓗsḨ->맆 };
like($@, qr/^Can't locate object method "맆"/, 'Unicode SUPER error renders terminal method');
unlike($@, qr/ᔅᓗsḨ::SUPER::맆/, 'Unicode lookup qualification is not rendered');
