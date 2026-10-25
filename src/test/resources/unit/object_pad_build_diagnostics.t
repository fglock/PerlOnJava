use strict;
use warnings;
use Test::More;

my $loaded = eval q{
    use Object::Pad 0.66;
    1;
};
if (!$loaded && $@ =~ /Can't locate Object\/Pad\.pm/) {
    plan skip_all => 'Object::Pad is not installed in system Perl';
}
die $@ unless $loaded;

my $malformed = eval q{
    use Object::Pad 0.66;
    class MalformedBuildDeclaration {
        BUILD method value {}
    }
    1;
};
ok(!$malformed, 'BUILD without a block body is rejected');
like($@, qr/Malformed BUILD declaration: expected a block body/,
    'malformed declaration gives a targeted BUILD diagnostic');

my $outside_class = eval q{
    BUILD {}
    1;
};
ok(!$outside_class, 'BUILD outside a class is rejected');
like($@, qr/BUILD blocks are only allowed inside class blocks/,
    'out-of-class diagnostic explains where BUILD is allowed');

done_testing;
