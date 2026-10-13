use strict;
use warnings;
use Test::More;

sub capture_anonymous_frame {
    package DB;
    @DB::args = ();
    my @frame = CORE::caller(1);
    return [$frame[3], [@DB::args]];
}

my $debug = sub { capture_anonymous_frame() };
*debug = $debug;
my $logger = bless {}, 'CallerAnonymousLogger';
my ($name, $args) = @{ debug($logger, 'msg') };
is($name, 'main::__ANON__', 'caller keeps a glob-installed anonymous sub anonymous');
is_deeply($args, [$logger, 'msg'], 'caller preserves anonymous frame arguments');

done_testing;
