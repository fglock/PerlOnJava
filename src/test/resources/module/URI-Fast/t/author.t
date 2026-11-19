use ExtUtils::testlib;
use Test2::V0;
use URI::Fast;
use URI::Fast::IRI;

skip_all 'Enable author tests by setting $ENV{AUTHOR_TESTING} to some true value'
  unless $ENV{RELEASE_TESTING}
      || $ENV{AUTHOR_TESTING}
      || $ENV{PERL_AUTHOR_TESTING};

is $URI::Fast::IRI::VERSION, $URI::Fast::VERSION, 'URI::Fast::IRI version number matches that of URI::Fast';

done_testing;
