use strict;
use warnings;
use Test::More;

require './src/main/perl/lib/ExtUtils/MM_PerlOnJava.pm';

my $mm = bless {}, 'ExtUtils::MM_PerlOnJava';
my $fragment = $mm->test(TESTS => 't/*.t');

like(
    $fragment,
    qr/JPERL_OPTS="\$\$\{PERLONJAVA_TEST_JPERL_OPTS:-\$\$JPERL_OPTS\}"/,
    'generated test command supports a child-specific JVM option override',
);
is(
    () = $fragment =~ /PERLONJAVA_TEST_JPERL_OPTS/g,
    2,
    'normal and dynamic test targets both apply the child JVM options',
);

open my $simplified, '<', './src/main/perl/lib/ExtUtils/MakeMaker.pm'
    or die "open simplified MakeMaker source: $!";
my $simplified_source = do { local $/; <$simplified> };
close $simplified or die "close simplified MakeMaker source: $!";

like(
    $simplified_source,
    qr/\$test_cmd = qq\{JPERL_OPTS="\\\$\\\$\{PERLONJAVA_TEST_JPERL_OPTS:-\\\$\\\$JPERL_OPTS\}" PERL5LIB=/,
    'simplified MakeMaker test harness supports the child JVM option override',
);
like(
    $simplified_source,
    qr/MakeMaker behavior\R\s*\$test_cmd = qq\{JPERL_OPTS=/,
    'the normal test-glob path uses the child JVM option override',
);

done_testing();
