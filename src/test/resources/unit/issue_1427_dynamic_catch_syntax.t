use strict;
use warnings;
use Test::More;

{
    package Local::DynamicCatch;

    sub catch {
        my ($class, $handler) = @_;
        return $class . ':' . $handler->();
    }
}

sub with (&;$) { return $_[0] }

my $exception_class = 'Local::DynamicCatch';
my $caught = catch $exception_class with { 'handler ran' };
is($caught, 'Local::DynamicCatch:handler ran',
    'dynamic class name dispatches catch with its with block');

done_testing;
