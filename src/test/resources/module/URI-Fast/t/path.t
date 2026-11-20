use utf8;
use ExtUtils::testlib;
use Test2::V0;
use URI::Fast qw(uri);

my $url = 'https://test.com/some/path?aaaa=bbbb&cccc=dddd&eeee=ffff';

subtest 'get' => sub{
  ok my $uri = uri($url), 'ctor';
  is $uri->path, '/some/path', 'get (scalar)';
  is [$uri->path], [qw(some path)], 'get (list)';
};

subtest 'set scalar' => sub{
  ok my $uri = uri($url), 'ctor';
  is $uri->path('/foo/bar'), '/foo/bar', 'set (scalar)';
  is $uri->path, '/foo/bar', 'get (scalar)';
  is [$uri->path], [qw(foo bar)], 'get (list)';
};

subtest 'set array' => sub{
  ok my $uri = uri($url), 'ctor';
  is $uri->path([qw(baz bat)]), '/baz/bat', 'set (array)';
  is $uri->path, '/baz/bat', 'get (scalar)';
  is [$uri->path], [qw(baz bat)], 'get (list)';

  subtest 'set array: segment w/ fwd slash' => sub{
    my $uri = uri('http://example.com');
    $uri->path(['foo', 'bar/baz']);

    is scalar($uri->path), '/foo/bar%2Fbaz', 'path(scalar)';
    is [$uri->path], ['foo', 'bar/baz'], 'path(list)';
    is $uri->to_string, 'http://example.com/foo/bar%2Fbaz', 'to_string';
  };
};

subtest 'compat' => sub{
  ok my $uri = uri('http://test.com/foo/bar'), 'ctor';
  is $uri->split_path_compat, ['', 'foo', 'bar'], 'includes empty leading segment';
};

subtest 'oddballs' => sub{
  ok lives{ uri->path([]) }, 'regression (bug #21): path([]) does not explode';
};

done_testing;
