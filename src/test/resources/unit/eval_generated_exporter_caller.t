use strict;
use warnings;
use Test::More;

sub reported_caller_package { scalar caller }

{
    package EvalGeneratedExporterCaller;

    sub import {
        my $target = caller;
        my $action = eval "package $target; sub { main::reported_caller_package() }";
        die $@ if $@;
        return $action->();
    }
}

package EvalGeneratedExporterCaller::Target;
my $got = EvalGeneratedExporterCaller->import;

package main;
is($got, 'EvalGeneratedExporterCaller::Target',
    'generated exporter closure reports its target package');
done_testing;
