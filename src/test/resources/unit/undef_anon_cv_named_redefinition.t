use strict;
use warnings;
use Test::More tests => 8;

# After `undef &$code` on an anonymous CV stored in a glob, a named
# definition through a glob alias fills that same CV in place, so saved
# references run the new body under the canonical glob name.

no warnings 'once';

my $aliased = sub { 'old body' };
*main::named = $aliased;
*main::alias = *main::named;
undef &$aliased;
ok(!defined &$aliased, 'undef empties the anonymous CV');

eval 'sub alias { (caller 0)[3] } 1' or die $@;
ok(defined &$aliased, 'a definition through the alias fills the same CV');
ok(\&main::named == $aliased, 'the glob keeps the same CV');
is($aliased->(), 'main::named', 'the saved reference runs the new body under the canonical name');
is(main::alias(), 'main::named', 'calling through the alias runs the new body');


# An anonymous sub compiled by eval STRING is an interpreted CV on both
# backends; the same in-place definition applies to it.
my $from_eval = eval 'sub { "old eval body" }' or die $@;
*main::eval_named = $from_eval;
*main::eval_alias = *main::eval_named;
undef &$from_eval;
eval 'sub eval_alias { "new eval body" } 1' or die $@;
ok(defined &$from_eval, 'an eval-compiled anonymous CV receives the definition');
is($from_eval->(), 'new eval body', 'the eval-compiled reference runs the new body');
is(main::eval_named(), 'new eval body', 'the canonical glob runs the new body');
