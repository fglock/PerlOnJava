use ExtUtils::testlib;
use Test2::V0;
use URI::Fast qw(uri);
use URI::Fast::IRI;

package Mock;
use parent 'URI::Fast';
1;

package main;

# Constructor returns object of expected class
ok my $obj = Mock->new('http://www.example.com'), 'ctor';
isa_ok $obj, ['Mock', 'URI::Fast'];

# absolute() builds a new object
my $abs = Mock->new('g')->absolute('http://a/b/c/d;p?q');
isa_ok $abs, ['Mock', 'URI::Fast'], 'absolute';

# relative() builds a new object
my $rel = Mock->new('http://a/b/c')->relative('http://a/b');
isa_ok $rel, ['Mock', 'URI::Fast'], 'relative';

# IRIs
my $iri = URI::Fast::IRI->new('http://www.example.com');
isa_ok $iri, ['URI::Fast::IRI', 'URI::Fast'], 'URI::Fast::IRI constructor';

done_testing;
