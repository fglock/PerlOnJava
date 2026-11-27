use strict;
use warnings;
use Test::More;
use POSIX::strptime qw(strptime);

if ($^O eq 'MSWin32') {
    my $supported = eval { strptime('10:32:34', '%H:%M:%S'); 1 };
    ok(!$supported && $@ =~ /not supported on this platform/,
        'provider reports unsupported platforms explicitly');
    done_testing;
    exit;
}

my @date = strptime('2005-02-04 10:32:34', '%F %T');
is_deeply([@date[0 .. 5]], [34, 32, 10, 4, 1, 105],
    'exported strptime parses date and time fields');
is(scalar @date, 9, 'strptime returns all nine struct tm fields');

my @time = POSIX::strptime('10:32:34', '%H:%M:%S');
is_deeply([@time[0 .. 2]], [34, 32, 10],
    'fully qualified strptime parses time fields');
ok(!defined($time[3]) && !defined($time[4]) && !defined($time[5]),
    'fields not set by the format are undef');

my @invalid = strptime('blah blah', '%H:%M:%S');
ok(!defined($invalid[0]) && !defined($invalid[1]) && !defined($invalid[2]),
    'invalid input leaves parsed fields undef');

done_testing;
