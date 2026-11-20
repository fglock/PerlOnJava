use strict;
use warnings;
use Test::More tests => 4;

{
    package Invocant;
    our $checks = 0;
    sub check { $checks++; return $_[1] eq 'receiver' }
    sub get_message { 'invalid invocant' }
}

use Function::Parameters {
    def => {
        defaults => 'function',
        shift => [['$self' => bless({}, 'Invocant')]],
    },
};

def read_invocant($value) { [$self, $value] }

my $result = read_invocant('receiver', 'payload');
is($result->[0], 'receiver', 'configured shift parameter is bound as the invocant');
is($result->[1], 'payload', 'declared parameters follow the configured invocant');
is($Invocant::checks, 1, 'configured shift type checks the invocant');
my $invalid = eval { read_invocant('wrong', 'payload'); 1 };
ok(!$invalid && $@ =~ /invalid invocant/, 'configured shift rejects an invalid invocant');
