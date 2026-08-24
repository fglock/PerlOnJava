use strict;
use warnings;

use FindBin;
use Test::More;
use lib "$FindBin::Bin/../lib";
use PerlTestRunner::Timeouts qw(timeout_for_test);

is(timeout_for_test('perl5_t/t/re/anyof.t', 300), 1800,
    'direct anyof receives its measured completion floor');
is(timeout_for_test('perl5_t/t/re/anyof_thr.t', 300), 1800,
    'threaded anyof receives the same completion floor');
is(timeout_for_test('C:\\tree\\perl5_t\\t\\re\\anyof.t', 300), 1800,
    'anyof floor recognizes Windows paths');
is(timeout_for_test('perl5_t/t/re/anyof.t', 2000), 2000,
    'anyof preserves a larger caller timeout');
is(timeout_for_test('perl5_t/t/re/pat.t', 300), 900,
    'direct pat retains its production-load floor');
is(timeout_for_test('perl5_t/t/re/pat_thr.t', 1200), 1200,
    'threaded pat preserves a larger caller timeout');
is(timeout_for_test('perl5_t/t/re/pat_psycho.t', 300), 600,
    'stress fixtures retain their existing floor');
is(timeout_for_test('perl5_t/t/io/through.t', 450), 900,
    'through matrix keeps a proportional timeout');
is(timeout_for_test('src/test/resources/unit/array.t', 300), 300,
    'ordinary tests retain the caller timeout');

done_testing;
