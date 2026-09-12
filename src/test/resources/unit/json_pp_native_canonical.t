use strict;
use warnings;
use Test::More tests => 8;
use JSON::PP;

my $json = JSON::PP->new->canonical;
my $input = {
    zeta => "line\nquote\"",
    alpha => [ 1, JSON::PP::true, JSON::PP::false, undef ],
    beta => { number => 1.25, text => 'PerlOnJava' },
};

my $encoded = $json->encode($input);
is($encoded,
   '{"alpha":[1,true,false,null],"beta":{"number":1.25,"text":"PerlOnJava"},"zeta":"line\\nquote\""}',
   'canonical JSON encoding preserves ordering, booleans, and escapes');

my $decoded = $json->decode($encoded);
is_deeply($decoded->{alpha}[0], 1, 'canonical decoder preserves integer values');
ok($decoded->{alpha}[1], 'canonical decoder creates a true boolean');
ok(!$decoded->{alpha}[2], 'canonical decoder creates a false boolean');
is($decoded->{beta}{text}, 'PerlOnJava', 'canonical decoder preserves nested strings');

my $pretty = JSON::PP->new->pretty;
like($pretty->encode({ z => 1, a => 2 }), qr/\n/, 'non-canonical options retain the JSON::PP fallback');

my $nonref = JSON::PP->new->canonical->allow_nonref(0);
eval { $nonref->encode(1) };
like($@, qr/hash- or arrayref expected/i, 'allow_nonref false retains encoder fallback');
eval { $nonref->decode('1') };
like($@, qr/(?:hash- or arrayref expected|JSON text must be an object or array)/i,
    'allow_nonref false retains decoder fallback');
