use utf8;
use ExtUtils::testlib;
use Test2::V0;
use URI::Fast qw(uri);

subtest 'ipv4' => sub{
  ok my $uri = uri('http://usr:pwd@123.123.123.123:4242'), 'ctor';
  is $uri->scheme, 'http', 'scheme';
  is $uri->usr, 'usr', 'usr';
  is $uri->pwd, 'pwd', 'pwd';
  is $uri->host, '123.123.123.123', 'host';
  is $uri->port, '4242', 'port';
};

subtest 'ipv6' => sub{
  ok my $uri = uri('http://usr:pwd@[2001:db8::7]:4242'), 'ctor';
  is $uri->scheme, 'http', 'scheme';
  is $uri->usr, 'usr', 'usr';
  is $uri->pwd, 'pwd', 'pwd';
  is $uri->host, '[2001:db8::7]', 'host';
  is $uri->port, '4242', 'port';
};

done_testing;
