use strict;
use warnings FATAL => 'all';
use Test::More;
use Function::Parameters;

my @invalid_prototypes = (
    ['[', qr/Illegal character in prototype/],
    ['\\;', qr/Illegal character after '\\' in prototype/],
    ['\\[_;@]', qr/Illegal character after '\\' in prototype/],
    ['\\+', qr/Illegal character after '\\' in prototype/],
    ['\\\\', qr/Illegal character after '\\' in prototype/],
    ['[$]', qr/Illegal character in prototype/],
    ['\\[_$]', qr/Illegal character after '\\' in prototype/],
    ['_$', qr/Illegal character after '_' in prototype/],
);

for my $case (@invalid_prototypes) {
    my ($prototype, $diagnostic) = @$case;
    my $result = eval "fun () :prototype($prototype) {}";
    ok(!defined($result), "reject malformed prototype $prototype");
    like($@, $diagnostic, "report the malformed prototype $prototype");
}

my $duplicate_prototype = eval q{
    fun () :prototype($) prototype(@) {}
};
ok(!defined($duplicate_prototype), 'reject two prototype attributes');
like($@, qr/Can't redefine prototype/, 'duplicate prototype diagnostic is clear');

ok(eval 'fun () :prototype(_@) {}', 'accept underscore array prototype');
ok(eval 'fun () :prototype(_%) {}', 'accept underscore hash prototype');

done_testing;
