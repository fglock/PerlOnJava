#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

# Regression: eval STRING subs must return flattened my-hash values.
# Params::ValidationCompiler (DateTime stack) depends on this.

{
    my $code = eval q{
        sub {
            my %args;
            if (@_ % 2 == 0) { %args = @_; }
            return %args;
        }
    };
    ok($code, 'eval_closure-style sub compiles');

    my %got = $code->(name => 'foo');
    is($got{name}, 'foo', 'eval sub returns hash pairs from return %hash');

    $code = eval q{
        sub {
            my %args = (name => 'bar');
            return %args;
        }
    };
    %got = $code->();
    is($got{name}, 'bar', 'eval sub returns preset hash via return %hash');
}

done_testing();
