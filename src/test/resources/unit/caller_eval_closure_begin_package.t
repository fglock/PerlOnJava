use strict;
use warnings;
use Test::More;
use Eval::Closure qw(eval_closure);

our $reported_package;

my $compiled = eval_closure(
    source => q{
        package CallerEvalClosure::Provider;
        sub import {
            my $target = caller;
            $main::reported_package = $target;
            no strict 'refs';
            *{"${target}::new"} = sub { bless {}, shift };
        }

        package CallerEvalClosure::Target;
        BEGIN { CallerEvalClosure::Provider->import }
        sub { 1 }
    },
);

ok($compiled, 'eval closure compiles');
is($reported_package, 'CallerEvalClosure::Target',
    'BEGIN import sees the package declared in the eval-closure source');
my $object = CallerEvalClosure::Target->new;
isa_ok($object, 'CallerEvalClosure::Target',
    'caller package receives the generated constructor');

done_testing();
