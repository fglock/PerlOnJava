use strict;
use warnings;
use Test::More tests => 3;
use Scalar::Util qw(weaken);

{
    package EvalMapReturnCleanup;
    sub DESTROY { $main::EMRC_DESTROYED++ }
}

our ($EMRC_WEAK, $EMRC_DESTROYED);
$EMRC_DESTROYED = 0;

sub return_from_map_exits_eval {
    my $eval_value = eval {
        my $obj = bless {}, 'EvalMapReturnCleanup';
        $EMRC_WEAK = $obj;
        weaken($EMRC_WEAK);
        map { return 1 } 1;
        return 2;
    };
    return ($eval_value, 'after eval');
}

is_deeply(
    [ return_from_map_exits_eval() ],
    [ 1, 'after eval' ],
    'return from map exits the eval block and resumes the surrounding sub',
);

Internals::jperl_gc() if defined &Internals::jperl_gc;

ok !defined $EMRC_WEAK,
    'eval-block lexical is cleaned before non-local return rethrow';
is $EMRC_DESTROYED, 1,
    'DESTROY fires for eval-block lexical during non-local return unwind';
