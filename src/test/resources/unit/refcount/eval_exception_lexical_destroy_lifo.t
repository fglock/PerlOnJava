use strict;
use warnings;
use Test::More;

our @destroy_order;

{
    package EvalExceptionLexicalDestroyLifo::Probe;
    sub DESTROY {
        push @main::destroy_order, $_[0]{name};
    }
}

sub unwind_lexicals {
    eval {
        my $first = bless { name => 'first' }, 'EvalExceptionLexicalDestroyLifo::Probe';
        my $second = bless { name => 'second' }, 'EvalExceptionLexicalDestroyLifo::Probe';
        die "unwind lexical scope\n";
    };
}

unwind_lexicals();
is_deeply(\@destroy_order, [qw(second first)], 'exception cleanup destroys lexicals in reverse declaration order');

done_testing();
