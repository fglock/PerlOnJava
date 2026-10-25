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
    class MalformedBuildDeclaration {
        BUILD method value {}
    }
    1;
};
ok(!$malformed, 'BUILD without a block body is rejected');
like($@, qr/BUILD/i, 'malformed declaration diagnostic names BUILD');

my $outside_class = eval q{
    BUILD {}
    1;
};
ok(!$outside_class, 'BUILD outside a class is rejected');
like($@, qr/BUILD/i, 'out-of-class diagnostic names BUILD');

done_testing;
