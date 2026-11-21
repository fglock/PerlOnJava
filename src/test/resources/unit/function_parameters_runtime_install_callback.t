use strict;
use warnings;
use Test::More tests => 2;
use Function::Parameters;

my @installed;
use Function::Parameters {
    def => {
        defaults => 'function',
        runtime => 1,
        install_sub => fun ($name, $body) {
            push @installed, $name;
            no strict 'refs';
            *{"main::$name"} = $body;
        },
    },
};

def make_one($value) { $value + 1 }

is_deeply(\@installed, ['make_one'], 'configured installer receives the declared name');
is(make_one(41), 42, 'configured installer receives and installs the generated code');
