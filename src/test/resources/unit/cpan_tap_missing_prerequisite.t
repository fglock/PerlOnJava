use strict;
use warnings;
use Test::More;
use CPAN::Distribution;

plan skip_all => 'PerlOnJava CPAN prerequisite helper unavailable'
    unless CPAN::Distribution->can('_perlonjava_missing_modules_from_test_output');

my $output = <<'OUTPUT';
#   Failed test 'use Example;'
#     Error:  Can't locate List/MoreUtils.pm in @INC (you may need to install the List::MoreUtils module)
OUTPUT

is_deeply(
    [ CPAN::Distribution::_perlonjava_missing_modules_from_test_output($output) ],
    ['List::MoreUtils'],
    'TAP-indented missing-module diagnostics are promoted to prerequisites',
);

done_testing;
