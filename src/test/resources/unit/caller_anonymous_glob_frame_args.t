use strict;
use warnings;
use Test::More;

sub capture_logger_callback_frame {
    my $level = 1;
    while (my @frame = do {
        package DB;
        @DB::args = ();
        CORE::caller $level++;
    }) {
        next if $frame[0] =~ /^Log::Log4perl/;
        return [$frame[3], $frame[4], [@DB::args]];
    }
    return [];
}

{
    package Log::Log4perl::Layout::PatternLayout::Stacktrace;
    sub cspec { main::capture_logger_callback_frame() }
}

{
    package Log::Log4perl::Layout::PatternLayout;
    sub render { Log::Log4perl::Layout::PatternLayout::Stacktrace::cspec() }
}

{
    package Log::Log4perl::Logger;
    our $layout;
    BEGIN {
        *debug = sub {
            my ($self, $message) = @_;
            return $layout->render($message);
        };
    }
}

$Log::Log4perl::Logger::layout = bless {}, 'Log::Log4perl::Layout::PatternLayout';
my $logger = bless {}, 'Log::Log4perl::Logger';
sub l1 { $logger->debug('msg') }
my ($name, $has_args, $args) = @{ l1() };
is($name, 'Log::Log4perl::Logger::__ANON__',
    'caller preserves anonymous identity through nested logging callbacks');
ok($has_args, 'caller marks the duplicated anonymous callback frame as having arguments');
is_deeply($args, [$logger, 'msg'], 'caller preserves anonymous callback arguments');

done_testing;
