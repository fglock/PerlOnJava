use utf8;
use ExtUtils::testlib;
use Test2::V0;
use URI::Fast qw(uri);

subtest 'param' => sub{
  foreach my $sep (qw(& ;)) {
    subtest "separator '$sep'" => sub {
      my $uri = uri "http://www.test.com?foo=bar${sep}foo=baz${sep}fnord=slack";

      subtest 'context' => sub{
        is [$uri->param('foo')], [qw(bar baz)], 'get (list)';
        is $uri->param('fnord'), 'slack', 'get (scalar): single value as scalar';
        ok dies{ my $foo = $uri->param('foo'); }, 'get (scalar): dies when encountering multiple values';
      };

      subtest 'unset' => sub {
        is $uri->param('foo', undef, $sep), U, 'set';
        is $uri->param('foo'), U, 'get';
        is $uri->query, 'fnord=slack', 'updated: query';
      };

      subtest 'set: string' => sub {
        is $uri->param('foo', 'bar', $sep), 'bar', 'set (scalar, single value)';
        is $uri->param('foo'), 'bar', 'get';
        is $uri->query, "fnord=slack${sep}foo=bar", 'updated: query';
      };

      subtest 'set: array ref' => sub {
        is [$uri->param('foo', [qw(bar baz)], $sep)], [qw(bar baz)], 'set';
        is [$uri->param('foo')], [qw(bar baz)], 'get';
        is $uri->query, "fnord=slack${sep}foo=bar${sep}foo=baz", 'updated: query';
        is [$uri->param('qux', 'corge', $sep)], [qw(corge)], 'set qux';
        is [$uri->param('qux')], [qw(corge)], 'get qux';
        is $uri->query, "fnord=slack${sep}foo=bar${sep}foo=baz${sep}qux=corge", 'updated: query';
      };

      subtest 'whitespace in value' => sub{
        my $uri = uri;
        $uri->param('foo', 'bar baz');
        is $uri->param('foo'), 'bar baz', 'param: expected result';
        is $uri->query, 'foo=bar%20baz', 'param: expected result from query';

        $uri = uri;
        $uri->add_param('foo', 'bar baz');
        is $uri->param('foo'), 'bar baz', 'add_param: expected result from param';
        is $uri->query, 'foo=bar%20baz', 'add_param: expected result from query';
      };

      subtest 'edge cases' => sub {
        subtest 'empty parameter' => sub {
          my $uri = uri 'http://www.test.com?foo=';
          is $uri->param('foo'), '', 'expected param value';
        };

        subtest 'empty parameter w/ previous parameter parameter' => sub {
          my $uri = uri 'http://www.test.com?bar=baz&foo=';
          is $uri->param('foo'), '', 'expected param value';
        };

        subtest 'empty parameter w/ following parameter' => sub {
          my $uri = uri 'http://www.test.com?foo=&bar=baz';
          is $uri->param('foo'), '', 'expected param value';
        };

        subtest 'unset only parameter' => sub {
          my $uri = uri 'http://www.test.com?foo=bar';
          $uri->param('foo', undef, $sep);
          is $uri->query, '', 'expected query value';
        };

        subtest 'unset final parameter' => sub {
          my $uri = uri "http://www.test.com?bar=bat${sep}foo=bar";
          $uri->param('foo', undef, $sep);
          is $uri->query, 'bar=bat', 'expected query value';
        };

        subtest 'unset initial parameter' => sub {
          my $uri = uri "http://www.test.com?bar=bat${sep}foo=bar";
          $uri->param('bar', undef, $sep);
          is $uri->query, 'foo=bar', 'expected query value';
        };

        subtest 'update initial parameter' => sub {
          my $uri = uri "http://www.test.com?bar=bat${sep}foo=bar";
          $uri->param('bar', 'blah', $sep);
          is $uri->query, "foo=bar${sep}bar=blah", 'expected query value';
        };

        subtest 'update final parameter' => sub {
          my $uri = uri "http://www.test.com?bar=bat${sep}foo=bar";
          $uri->param('foo', 'blah', $sep);
          is $uri->query, "bar=bat${sep}foo=blah", 'expected query value';
        };

        subtest 'set: empty string' => sub {
          is uri->param('foo', ''), '', 'expected param value';
        };

        subtest 'set: zero' => sub {
          is uri->param('foo', '0'), '0', 'expected param value';
        };

        subtest 'set: space' => sub {
          is uri->param('foo', ' '), ' ', 'expected param value';
        };
      };
    };

    # https://github.com/sysread/URI-Fast/issues/23
    subtest 'set: array with zero-length string' => sub {
      my $u = uri;

      $u->param(foo => ['', 'bar']);
      is "$u", '?foo=&foo=bar', 'empty string as first value';

      $u->param(foo => ['bar', '']);
      is "$u", '?foo=bar&foo=', 'empty string as last value';

      $u->param(foo => ['bar', '', 'baz']);
      is "$u", '?foo=bar&foo=&foo=baz', 'empty string as middle value';
    };
  }

  subtest 'separator replacement' => sub {
    my $uri = uri 'http://example.com';

    $uri->param('foo', 'bar');
    $uri->param('baz', 'bat');
    like $uri->query, qr/&/, 'separator defaults to &';

    $uri->param('asdf', 'qwerty', ';');
    like $uri->query, qr/;/, 'explicit separator used';
    unlike $uri->query, qr/&/, 'original separator replaced';
  };
};

subtest 'add_param' => sub{
  my $uri = uri 'http://www.test.com';
  is $uri->param('foo', 'bar'), 'bar', 'param';
  is [$uri->add_param('foo', 'baz')], ['bar', 'baz'], 'add_param';
  is [$uri->param('foo')], ['bar', 'baz'], 'add_param';

  subtest 'separator replacement' => sub {
    my $uri = uri 'http://example.com';

    $uri->add_param('foo', 'bar');
    $uri->add_param('foo', 'baz');
    $uri->add_param('foo', 'bat');
    like $uri->query, qr/&/, 'separator defaults to &';

    $uri->add_param('asdf', 'qwerty', ';');
    like $uri->query, qr/;/, 'explicit separator used';
    unlike $uri->query, qr/&/, 'original separator replaced';
  };
};

done_testing;
