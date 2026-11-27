use strict;
use warnings;
use Test::More tests => 2;
use Scalar::Util qw(weaken);

our $weak_referent;
my $referent = bless {}, 'EvalNamedSubUnusedLexicalRelease::Probe';
$weak_referent = $referent;
weaken($weak_referent);
my $eval_source = '1';

sub eval_dynamic_source {
    return eval $eval_source;
}

eval_dynamic_source();
$eval_source = '$referent';
is(eval_dynamic_source(), $referent,
    'dynamic eval can still resolve a captured lexical');
$eval_source = '1';
eval_dynamic_source();

END {
    ok(!defined($weak_referent),
        'a named dynamic-eval sub does not retain an unrelated lexical referent');
}
