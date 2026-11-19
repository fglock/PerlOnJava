use utf8;
use ExtUtils::testlib;
use Test2::V0;
use URI::Fast qw(uri);

my $E = '%E1%BF%AC';
my $e = '%e1%bf%ac';

is uri('http://example.com')->normalize, 'http://example.com/', 'lc scheme';
is uri('HTTP://example.com')->normalize, 'http://example.com/', 'lc scheme';
is uri('http://EXAMPLE.com')->normalize, 'http://example.com/', 'lc host';
is uri('/foo/../bar')->normalize, '/bar', 'path: remove dot segments';

subtest 'normalize casing of encoding' => sub{
  is uri("?foo=$e")->normalize, "?foo=$E", 'query - ?k=v';
  is uri("?$e&$e")->normalize, "?$E&$E", 'query - ?k&k';
  is uri("http://foo${e}\@bar.com")->normalize, "http://foo${E}\@bar.com/", 'user';
  is uri("http://foo:${e}\@bar.com")->normalize, "http://foo:${E}\@bar.com/", 'pwd';
  is uri("http://foo${e}bar.com")->normalize, "http://foo${E}bar.com/", 'host';
  is uri("http://example.com/foo/$e/$e")->normalize, "http://example.com/foo/$E/$E", 'path';
  is uri("http://example.com/foo#$e")->normalize, "http://example.com/foo#$E", 'frag';
};

subtest 'normalize encoding' => sub{
  is uri('?foo=bar+bat')->normalize, '?foo=bar%20bat', '+ converted to %20';
  is uri(sprintf('?foo=%%%X', ord('x')))->normalize, '?foo=x', 'encoded unreserved chars decoded';
};

done_testing;
