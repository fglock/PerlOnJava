use strict;
use warnings;
use Test::More tests => 1;
use Test2::API qw/context intercept/;

sub _test2_hint_from_caller {
    my ($level) = @_;
    my $hint_hash = (caller($level))[10] || {};
    return $hint_hash->{'Test2::Tools::LoadModule/load_error'};
}

sub _test2_validate_load_options {
    my $load_error = _test2_hint_from_caller(2);
    return defined($load_error) ? $load_error : '%s';
}

sub _test2_load_module_ok {
    my $load_error = _test2_validate_load_options();
    my @diagnostics;
    push @diagnostics, 'load diagnostic' if $load_error;

    my $ctx = context();
    return $ctx->fail_and_release('module load failed', @diagnostics);
}

SKIP: {
    BEGIN { $^H{'Test2::Tools::LoadModule/load_error'} = 0 }

    my $events = intercept {
        _test2_load_module_ok();
    };
    my ($failure) = grep { $_->isa('Test2::Event::Fail') } @$events;

    ok($failure && !defined($failure->info),
       'suppressed load diagnostics leave failure event info undefined');
}
