use utf8;
use ExtUtils::testlib;
use Test2::V0;
use URI::Fast qw(uri);

subtest basics => sub{
  my $uri = uri 'http://www.example.com/foo/bar?foo=bar&baz=bat';
  ok $uri->compare('http://www.example.com/foo/bar?foo=bar&baz=bat'), 'identical input string';
  ok $uri->compare('http://www.example.com/foo/bar?baz=bat&foo=bar'), 'query parameter order does not matter';
};

subtest scheme => sub{
  ok uri('http://')->compare('http://'), 'identical';
  ok !uri('http://')->compare('https://'), 'different';

  ok !uri('http://')->compare(''), 'string -> empty';
  ok !uri('')->compare('http://'), 'empty -> string';

  ok !uri('http://')->compare(undef), 'string -> undef';
  ok !uri(undef)->compare('http://'), 'undef -> string';
};

subtest host => sub{
  ok uri('www.example.com')->compare('www.example.com'), 'identical';
  ok !uri('www.example.com')->compare('www.exumple.com'), 'different';

  ok !uri('www.example.com')->compare(''), 'string -> empty';
  ok !uri('')->compare('www.example.com'), 'empty -> string';

  ok !uri('www.example.com')->compare(undef), 'string -> undef';
  ok !uri(undef)->compare('www.example.com'), 'undef -> string';
};

subtest port => sub{
  ok uri('www.example.com:1234')->compare('www.example.com:1234'), 'identical';
  ok !uri('www.example.com:1234')->compare('www.example.com:4321'), 'different';

  ok !uri('www.example.com:1234')->compare('www.example.com'), 'string -> empty';
  ok !uri('www.example.com')->compare('www.example.com:1234'), 'empty -> string';

  ok !uri('www.example.com:1234')->compare(undef), 'string -> undef';
  ok !uri(undef)->compare('www.example.com:1234'), 'undef -> string';
};

subtest usr => sub{
  ok uri('usr@www.example.com')->compare('usr@www.example.com'), 'identical';
  ok !uri('usr@www.example.com')->compare('someone@www.example.com'), 'different';

  ok !uri('usr@www.example.com')->compare('www.example.com'), 'string -> empty';
  ok !uri('www.example.com')->compare('usr@www.example.com'), 'empty -> string';

  ok !uri('usr@www.example.com')->compare(undef), 'string -> undef';
  ok !uri(undef)->compare('usr@www.example.com'), 'undef -> string';
};

subtest pwd => sub{
  ok uri('usr:pwd@www.example.com')->compare('usr:pwd@www.example.com'), 'identical';
  ok !uri('usr:pwd@www.example.com')->compare('usr:secret@www.exumple.com'), 'different';

  ok !uri('usr:pwd@www.example.com')->compare('usr@www.example.com'), 'string -> empty';
  ok !uri('usr@www.example.com')->compare('usr:pwd@www.example.com'), 'empty -> string';

  ok !uri('usr:pwd@www.example.com')->compare(undef), 'string -> undef';
  ok !uri(undef)->compare('usr:pwd@www.example.com'), 'undef -> string';
};

subtest frag => sub{
  ok uri('#foo')->compare('#foo'), 'identical';
  ok !uri('#foo')->compare('#bar'), 'different';

  ok !uri('#foo')->compare('#'), 'string -> empty';
  ok !uri('#')->compare('#foo'), 'empty -> string';

  ok !uri('#foo')->compare(undef), 'string -> undef';
  ok !uri(undef)->compare('#foo'), 'undef -> string';
};

subtest path => sub{
  ok uri('/foo/bar')->compare('/foo/bar'), 'identical';
  ok !uri('/foo/bar')->compare('/bar/foo'), 'different';

  ok !uri('/foo/bar')->compare('/'), 'string -> "/"';
  ok !uri('/foo/bar')->compare(''), 'string -> empty';

  ok !uri('/')->compare('/foo/bar'), 'empty -> string';
  ok !uri('')->compare('/foo/bar'), '"/" -> string';

  ok !uri('/foo/bar')->compare(undef), 'string -> undef';
  ok !uri(undef)->compare('/foo/bar'), 'undef -> string';
};

subtest query => sub{
  ok uri('?foo=bar&baz=bat')->compare('?foo=bar&baz=bat'), 'identical';
  ok uri('?foo=bar&baz=bat')->compare('?baz=bat&foo=bar'), 'ordering';
  ok !uri('?foo=bar&baz=bat')->compare('?baz=bat'), 'one key missing';
  ok !uri('?foo=bar&baz=bat')->compare('?foo=bar&baz='), 'one value missing';
  ok !uri('?foo=bar&baz=bat')->compare(''), 'empty string';
  ok !uri('?foo=bar&baz=bat')->compare(undef), 'undef';
};

done_testing;
