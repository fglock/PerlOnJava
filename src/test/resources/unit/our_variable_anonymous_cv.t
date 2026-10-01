use strict;
use warnings;
use Test::More tests => 1;

{
    package Local::OurVariableAnonymousCV;
    our $value;

    sub install_accessor {
        *accessor = sub () { $value };
        $value = 'updated after CV creation';
    }

    install_accessor();
}

is(Local::OurVariableAnonymousCV::accessor(), 'updated after CV creation',
    'anonymous constant-prototype CV reads a live package variable');
