use utf8;
use ExtUtils::testlib;
use Test2::V0;
use URI::Fast qw(uri);

my @uris = (
  '/foo/bar/baz',
  'http://www.test.com',
  'https://test.com/some/path?aaaa=bbbb&cccc=dddd&eeee=ffff',
  'https://user:pwd@192.168.0.1:8000/foo/bar?baz=bat&slack=fnord&asdf=the+quick%20brown+fox+%26+hound#foofrag',
);

ok(uri($_), "uri: $_") foreach @uris;

is uri(undef), '', 'undef';
is uri(''), '', 'empty string';

is uri('/foo')->scheme, '', 'missing scheme';
is uri('http:'), 'http:', 'non-file scheme w/o host';
is uri('http://test'), 'http://test', 'auth w/ invalid host';

is uri('http://usr:pwd')->usr, '', 'no usr w/o @';
is uri('http://usr:pwd')->pwd, '', 'no pwd w/o @';
is uri('http://usr:pwd')->host, 'usr', 'host w/ invalid port';
is uri('http://usr:pwd')->port, 'pwd', 'invalid port number';

is uri('#')->frag, '', 'fragment empty but starts with #';

subtest 'param' => sub{
  is uri('?')->param('foo'), undef, 'empty query';
  is uri('?foo')->param('foo'), undef, 'query key w/o =value';
  is uri('?foo=')->param('foo'), '', 'query key w/o value';
  is uri('?=bar')->param('foo'), undef, 'query =value w/o key && request key ne value';
  is uri('?=bar')->param('bar'), undef, 'query =value w/o key && request key eq value';
  is uri('?=')->param('foo'), undef, 'query w/ = but w/o key or value';
  is uri('???')->param('??'), undef, 'multiple question marks';
  is uri('?food=bard&foo=bar')->param('foo'), 'bar', 'substring match';

  subtest 'edge cases' => sub{
    subtest 'bad input: unencoded = in query param value' => sub{
      my $q = '?url=http%3A%2F%2Fwww.example.com%2Fsome%2Fpath%3Fencparam=fnord&foo=bar';
      my $u = uri $q;
      is scalar($u->param('url')), 'http://www.example.com/some/path?encparam=fnord', 'invalid param';
      is scalar($u->param('foo')), 'bar', 'valid param';
    };
  };
};

subtest 'query_hash' => sub{
  is uri('?')->query_hash, hash{ end }, 'empty query';
  is uri('?foo')->query_hash, hash{ field 'foo' => array{ end }; end }, 'query key w/o =value';
  is uri('?foo')->query_hash, hash{ field 'foo' => array{ end }; end }, 'query key w/o =value';
  is uri('?foo=')->query_hash, hash{ field 'foo' => array{ item ''; end }; end }, 'query key w/o value';
  is uri('?=bar')->query_hash, hash{ end }, 'query =value w/o key';
  is uri('?=')->query_hash, hash{ end }, 'query w/ = but w/o key or value';
  is uri('???')->query_hash, hash{ field '??' => array{ end }; end }, 'multiple question marks';
};

subtest 'split_path' => sub{
  is uri('//foo/baz.png')->split_path, array{
    item 'baz.png';
    end;
  }, 'double leading slashes';

  is uri('/foo/bar/')->split_path, array{
    item 'foo';
    item 'bar';
    end;
  }, 'trailing slash';

  is uri('/foo/bar//')->split_path, array{
    item 'foo';
    item 'bar';
    item '';
    end;
  }, 'double trailing slashes';

  is uri('/foo//bar')->split_path, array{
    item 'foo';
    item '';
    item 'bar';
    end;
  }, 'double internal slashes';
};

done_testing;
