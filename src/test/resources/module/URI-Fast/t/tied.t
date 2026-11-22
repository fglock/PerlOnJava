package TiedSV;
require Tie::Scalar;
@ISA = qw(Tie::StdScalar);
1;

package main;
use ExtUtils::testlib;
use Test2::V0;
use URI::Fast qw(uri uri_split);

subtest 'uri_split' => sub{
  tie my $str, 'TiedSV', 'http://www.test.com';
  ok my @parts = uri_split($str), 'call';
  is \@parts, array{item 'http'; item 'www.test.com'; item ''; item U; item U;}, 'check';
};

subtest 'uri' => sub{
  tie my $str, 'TiedSV', 'http://www.test.com';
  ok my $uri = uri($str), 'call';
  is $uri->host, 'www.test.com', 'check';
};

subtest 'set_param' => sub{
  subtest 'scalars' => sub{
    tie my $key, 'TiedSV', 'foo';
    tie my $val, 'TiedSV', 'bar';
    my $uri = uri 'http://www.test.com';
    ok lives{ $uri->set_param($key, [$val], '&') }, 'call';
    $uri->set_param($key, [$val], '&');
    is $uri->param('foo'), 'bar', 'check';
  };

  subtest 'arrays' => sub{
    tie my $val, 'TiedSV', ['bar'];
    my $uri = uri 'http://www.test.com';
    ok lives{ $uri->set_param('foo', $val, '&') }, 'call';
    is $uri->param('foo'), 'bar', 'check';
  };
};

subtest 'get_param' => sub{
  tie my $key, 'TiedSV', 'foo';
  my $uri = uri 'http://www.test.com?foo=bar';
  is $uri->get_param($key), ['bar'], 'call/check';
};

subtest 'decode' => sub{
  tie my $encoded, 'TiedSV', 'foo%20bar';
  ok my $decoded = URI::Fast::decode($encoded), 'call';
  is $decoded, 'foo bar', 'check';
};

subtest 'encode' => sub{
  tie my $decoded, 'TiedSV', 'foo bar';
  ok my $encoded = URI::Fast::encode($decoded), 'call';
  is $encoded, 'foo%20bar', 'check';
};

done_testing;
